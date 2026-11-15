package com.nexus.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.notification.NotificationServiceApplication;
import com.nexus.notification.application.backfill.BackfillMembershipUseCase;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.proto.community.v1.StreamAllMembersRequest;
import com.nexus.proto.community.v1.StreamAllMembersResponse;
import com.nexus.proto.messaging.v1.SocialServiceGrpc;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsRequest;
import com.nexus.proto.messaging.v1.StreamAllDmChannelsResponse;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra backfill lúc khởi động với broker riêng, Postgres thật và gRPC giả Community, Messaging
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@TestPropertySource(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=true",
      "nexus.notification.backfill.enabled=true",
      "nexus.notification.backfill.retry-delay=10ms"
    })
class BackfillRolloutIT extends NotificationIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String COMMUNITY_QUEUE = "notification.community.events";
  private static final String MESSAGING_QUEUE = "notification.messaging.events";
  private static final Instant JOINED = Instant.now().minus(30, ChronoUnit.DAYS);
  private static final UUID SERVER = UUID.randomUUID();
  private static final UUID SERVER2 = UUID.randomUUID();
  private static final UUID DM = UUID.randomUUID();
  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();
  private static final UUID C = UUID.randomUUID();
  private static final UUID LEFT_EVENT = UUID.randomUUID();
  private static final UUID JOINED_EVENT = UUID.randomUUID();
  private static final RabbitMQContainer BROKER = broker();
  private static final AtomicInteger MEMBER_CALLS = new AtomicInteger();
  private static final AtomicInteger DM_CALLS = new AtomicInteger();
  private static final AtomicInteger UNAVAILABLE_CALLS = new AtomicInteger();
  private static final Server SOURCE = start(new FakeCommunity(), new FakeMessaging());
  private static final Server DOWN = start(new DownCommunity(), new DownMessaging());

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitListenerEndpointRegistry registry;
  @Autowired private BackfillMembershipUseCase backfill;
  @Autowired private Environment env;
  @LocalServerPort private int port;

  // Trỏ tới broker riêng để listener của context IT khác không ăn event, hai client gRPC tới SOURCE
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", BROKER::getHost);
    registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
    registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
    registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
    registry.add("app.grpc.community.target", () -> "dns:///localhost:" + SOURCE.getPort());
    registry.add("app.grpc.messaging.target", () -> "dns:///localhost:" + SOURCE.getPort());
  }

  // Kịch bản: member có từ trước khi bật notification được mention trong kênh server
  // Input: snapshot có B ở SERVER (joinedAt cũ), MessageCreated kênh mới của SERVER mention B
  // Output: mention_count của B = 1, PUT /read-states/{channel} của B trả 200
  @Test
  void existingServerMemberGetsMentionAndCanMarkRead() throws Exception {
    UUID channel = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();

    UUID eventId = message(messageId, channel, SERVER, A, B);

    awaitProcessed(eventId, MESSAGING_QUEUE);
    assertThat(mentionCount(B, channel)).isEqualTo(1);
    String body = "{\"lastReadMessageId\":\"" + messageId + "\"}";
    assertThat(send("PUT", "/read-states/" + channel, B, body).statusCode()).isEqualTo(200);
  }

  // Kịch bản: DM có từ trước, không có DmChannelCreated nào trong queue
  // Input: snapshot DM (A, B), MessageCreated serverId null của B mention A
  // Output: mention_count của A = 1, GET /read-states của A thấy kênh DM unread
  @Test
  void existingDmWorksWithoutDmChannelCreatedEvent() throws Exception {
    UUID eventId = message(UUID.randomUUID(), DM, null, B, A);

    awaitProcessed(eventId, MESSAGING_QUEUE);
    assertThat(mentionCount(A, DM)).isEqualTo(1);
    JsonNode items = MAPPER.readTree(send("GET", "/read-states", A, null).body()).get("items");
    JsonNode dm = null;
    for (JsonNode item : items) {
      if (item.get("channelId").asText().equals(DM.toString())) {
        dm = item;
      }
    }
    assertThat(dm).isNotNull();
    assertThat(dm.get("unread").asBoolean()).isTrue();
  }

  // Kịch bản: event đến queue sau khi khai báo, trước khi listener chạy, được replay trên snapshot
  // Input: lúc đọc snapshot publish MemberLeft(SERVER2, B), MemberJoined(SERVER2, C); snapshot có B
  // Output: B không còn ở SERVER2, C là member SERVER2, B vẫn ở SERVER
  @Test
  void eventsBufferedBeforeBackfillReplayAfterSnapshot() {
    awaitProcessed(LEFT_EVENT, COMMUNITY_QUEUE);
    awaitProcessed(JOINED_EVENT, COMMUNITY_QUEUE);

    assertThat(isMember(SERVER2, B)).isFalse();
    assertThat(isMember(SERVER2, C)).isTrue();
    assertThat(isMember(SERVER, B)).isTrue();
  }

  // Kịch bản: backfill đã đánh dấu thì không chạy lại
  // Input: context đã khởi động một lần, gọi execute() thêm lần nữa
  // Output: execute() trả false, mỗi stream của nguồn chỉ bị gọi 1 lần
  @Test
  void backfillRunsOnlyOnce() {
    assertThat(backfill.execute()).isFalse();

    assertThat(MEMBER_CALLS.get()).isEqualTo(1);
    assertThat(DM_CALLS.get()).isEqualTo(1);
    assertThat(markers()).isEqualTo(1);
  }

  // Kịch bản: Community luôn UNAVAILABLE khi một instance mới khởi động chưa có dòng đánh dấu
  // Input: xóa membership-v1, dừng listener, publish 1 event, khởi động context mới trỏ tới DOWN
  // Output: khởi động lỗi sau 3 lần thử, backfill_state rỗng, event vẫn nằm trong queue
  @Test
  void sourceUnavailableFailsStartup() {
    awaitProcessed(LEFT_EVENT, COMMUNITY_QUEUE);
    awaitProcessed(JOINED_EVENT, COMMUNITY_QUEUE);
    jdbc.update("delete from notification.backfill_state");
    registry.getListenerContainer("community-events").stop();
    registry.getListenerContainer("messaging-events").stop();
    try {
      publish("community.events", "member.joined", UUID.randomUUID(), "MemberJoined", join(C));
      await().atMost(Duration.ofSeconds(5)).until(() -> messages(COMMUNITY_QUEUE) == 1);

      assertThatThrownBy(() -> failingContext().close())
          .hasRootCauseInstanceOf(StatusRuntimeException.class);

      assertThat(UNAVAILABLE_CALLS.get()).isEqualTo(3);
      assertThat(markers()).isZero();
      assertThat(messages(COMMUNITY_QUEUE)).isEqualTo(1);
    } finally {
      jdbc.update(
          "insert into notification.backfill_state (name, completed_at)"
              + " values ('membership-v1', now()) on conflict do nothing");
      registry.getListenerContainer("community-events").start();
      registry.getListenerContainer("messaging-events").start();
    }
  }

  // Khởi động context notification thứ hai cùng hạ tầng, nguồn snapshot DOWN, qua tham số dòng lệnh
  private ConfigurableApplicationContext failingContext() {
    Map<String, Object> props = new HashMap<>();
    for (String key :
        List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.rabbitmq.host",
            "spring.rabbitmq.port",
            "spring.rabbitmq.username",
            "spring.rabbitmq.password",
            "app.jwt.jwks-uri")) {
      props.put(key, env.getRequiredProperty(key));
    }
    props.put("spring.rabbitmq.virtual-host", "/");
    props.put("server.port", "0");
    props.put("management.server.port", "0");
    props.put("nexus.messaging.relay.enabled", "false");
    props.put("nexus.notification.backfill.retry-delay", "10ms");
    props.put("nexus.notification.backfill.max-attempts", "3");
    props.put("app.grpc.community.target", "dns:///localhost:" + DOWN.getPort());
    props.put("app.grpc.messaging.target", "dns:///localhost:" + DOWN.getPort());
    String[] args =
        props.entrySet().stream()
            .map(e -> "--" + e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
    return new SpringApplicationBuilder(NotificationServiceApplication.class).run(args);
  }

  // Khởi động broker RabbitMQ riêng của lớp này, Ryuk dọn khi JVM kết thúc
  private static RabbitMQContainer broker() {
    RabbitMQContainer container = new RabbitMQContainer("rabbitmq:4-management-alpine");
    container.start();
    return container;
  }

  // Khởi động server gRPC thật trên cổng ngẫu nhiên với hai service cho trước
  private static Server start(
      PermissionServiceGrpc.PermissionServiceImplBase community,
      SocialServiceGrpc.SocialServiceImplBase messaging) {
    try {
      return NettyServerBuilder.forPort(0)
          .addService(community)
          .addService(messaging)
          .build()
          .start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // Community giả: lần đầu publish hai event SERVER2 rồi stream A, B ở SERVER và B ở SERVER2
  private static final class FakeCommunity extends PermissionServiceGrpc.PermissionServiceImplBase {

    // Đếm lần gọi, đệm event vào queue đã khai báo rồi trả snapshot member
    @Override
    public void streamAllMembers(
        StreamAllMembersRequest request, StreamObserver<StreamAllMembersResponse> observer) {
      if (MEMBER_CALLS.incrementAndGet() == 1) {
        bufferEvents();
      }
      observer.onNext(record(SERVER, A));
      observer.onNext(record(SERVER, B));
      observer.onNext(record(SERVER2, B));
      observer.onCompleted();
    }

    // Bản ghi member với joinedAt cũ
    private static StreamAllMembersResponse record(UUID server, UUID user) {
      return StreamAllMembersResponse.newBuilder()
          .setServerId(server.toString())
          .setUserId(user.toString())
          .setJoinedAt(
              Timestamp.newBuilder()
                  .setSeconds(JOINED.getEpochSecond())
                  .setNanos(JOINED.getNano())
                  .build())
          .build();
    }
  }

  // Messaging giả: đếm lần gọi và stream một kênh DM giữa A và B
  private static final class FakeMessaging extends SocialServiceGrpc.SocialServiceImplBase {

    // Trả snapshot một kênh DM
    @Override
    public void streamAllDmChannels(
        StreamAllDmChannelsRequest request, StreamObserver<StreamAllDmChannelsResponse> observer) {
      DM_CALLS.incrementAndGet();
      observer.onNext(
          StreamAllDmChannelsResponse.newBuilder()
              .setChannelId(DM.toString())
              .setUserLowId(A.toString())
              .setUserHighId(B.toString())
              .build());
      observer.onCompleted();
    }
  }

  // Community giả luôn UNAVAILABLE
  private static final class DownCommunity extends PermissionServiceGrpc.PermissionServiceImplBase {

    // Đếm lần gọi rồi trả UNAVAILABLE
    @Override
    public void streamAllMembers(
        StreamAllMembersRequest request, StreamObserver<StreamAllMembersResponse> observer) {
      UNAVAILABLE_CALLS.incrementAndGet();
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }

  // Messaging giả luôn UNAVAILABLE
  private static final class DownMessaging extends SocialServiceGrpc.SocialServiceImplBase {

    // Trả UNAVAILABLE
    @Override
    public void streamAllDmChannels(
        StreamAllDmChannelsRequest request, StreamObserver<StreamAllDmChannelsResponse> observer) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }

  // Publish MemberLeft của B và MemberJoined của C ở SERVER2 bằng client AMQP riêng, chờ confirm
  private static void bufferEvents() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(BROKER.getHost());
    factory.setPort(BROKER.getAmqpPort());
    factory.setUsername(BROKER.getAdminUsername());
    factory.setPassword(BROKER.getAdminPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.confirmSelect();
      rawPublish(channel, "member.left", LEFT_EVENT, "MemberLeft", join(B));
      rawPublish(channel, "member.joined", JOINED_EVENT, "MemberJoined", join(C));
      channel.waitForConfirmsOrDie(5000);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Gửi envelope community qua channel AMQP thô
  private static void rawPublish(
      Channel channel, String routingKey, UUID eventId, String type, Map<String, Object> payload)
      throws IOException {
    AMQP.BasicProperties props =
        new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .messageId(eventId.toString())
            .deliveryMode(2)
            .build();
    channel.basicPublish(
        "community.events", routingKey, props, envelope(eventId, type, payload, "community"));
  }

  // Payload member của user ở SERVER2, joinedAt bây giờ
  private static Map<String, Object> join(UUID user) {
    return Map.of("serverId", SERVER2, "userId", user, "joinedAt", Instant.now().toString());
  }

  // Envelope JSON của event với producer cho trước
  private static byte[] envelope(
      UUID eventId, String type, Map<String, Object> payload, String producer) {
    return MAPPER.writeValueAsBytes(
        Map.of(
            "eventId",
            eventId,
            "eventType",
            type,
            "eventVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "producer",
            producer + "-service",
            "aggregateType",
            "Test",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload));
  }

  // Publish MessageCreated createdAt bây giờ, trả eventId
  private UUID message(UUID messageId, UUID channel, UUID server, UUID author, UUID mention) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("messageId", messageId);
    payload.put("channelId", channel);
    payload.put("serverId", server);
    payload.put("authorId", author);
    payload.put("content", "hi");
    payload.put("mentionUserIds", List.of(mention));
    payload.put("createdAt", Instant.now().toString());
    UUID eventId = UUID.randomUUID();
    publish("messaging.events", "message.created", eventId, "MessageCreated", payload);
    return eventId;
  }

  // Gửi envelope vào exchange bằng RabbitTemplate của context
  private void publish(
      String exchange, String routingKey, UUID eventId, String type, Map<String, Object> payload) {
    rabbit.send(
        exchange,
        routingKey,
        MessageBuilder.withBody(envelope(eventId, type, payload, exchange.split("\\.")[0]))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Gọi API với Bearer của user, body null thì không gửi body
  private HttpResponse<String> send(String method, String path, UUID user, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer(user));
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Số message sẵn sàng trong queue
  private long messages(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }

  // Số dòng đánh dấu membership-v1
  private int markers() {
    return jdbc.queryForObject(
        "select count(*) from notification.backfill_state where name = 'membership-v1'",
        Integer.class);
  }

  // True nếu user có dòng server_members ở server
  private boolean isMember(UUID server, UUID user) {
    return jdbc.queryForObject(
            "select count(*) from notification.server_members where server_id = ? and user_id = ?",
            Integer.class,
            server,
            user)
        == 1;
  }

  // mention_count của user trong kênh, 0 nếu chưa có dòng
  private int mentionCount(UUID user, UUID channel) {
    return jdbc.queryForObject(
        "select coalesce(max(mention_count), 0) from notification.read_states"
            + " where user_id = ? and channel_id = ?",
        Integer.class,
        user,
        channel);
  }

  // Chờ tới khi consumer đã commit event vào inbox
  private void awaitProcessed(UUID eventId, String consumer) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from notification.processed_event"
                            + " where event_id = ? and consumer = ?",
                        Integer.class,
                        eventId,
                        consumer)
                    == 1);
  }
}

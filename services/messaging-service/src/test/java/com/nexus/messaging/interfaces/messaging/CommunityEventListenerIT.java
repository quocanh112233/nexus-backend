package com.nexus.messaging.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.PermissionVersion;
import com.nexus.proto.community.v1.CheckChannelPermissionsRequest;
import com.nexus.proto.community.v1.CheckChannelPermissionsResponse;
import com.nexus.proto.community.v1.Permission;
import com.nexus.proto.community.v1.PermissionServiceGrpc;
import com.nexus.testsupport.RabbitMqContainer;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra listener community.events với RabbitMQ, Postgres, Redis thật và gRPC giả luôn trả OK
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class CommunityEventListenerIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String QUEUE = "messaging.community.events";
  private static final UUID SERVER = UUID.randomUUID();
  private static final Server COMMUNITY = startFakeCommunity();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private StringRedisTemplate redis;
  @MockitoSpyBean private PermissionVersion permissionVersion;
  @LocalServerPort private int port;

  // Trỏ broker tới container (vhost "/") và client Community tới server gRPC giả
  @DynamicPropertySource
  static void broker(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
    registry.add("app.grpc.community.target", () -> "dns:///localhost:" + COMMUNITY.getPort());
  }

  // Dựng server gRPC thật luôn trả OK: server cố định, quyền xem và gửi tin
  private static Server startFakeCommunity() {
    try {
      return NettyServerBuilder.forPort(0)
          .addService(
              // Server gRPC giả của Community luôn cấp quyền xem và gửi
              new PermissionServiceGrpc.PermissionServiceImplBase() {
                // Trả về serverId cố định cùng hai quyền VIEW_CHANNEL, SEND_MESSAGES
                @Override
                public void checkChannelPermissions(
                    CheckChannelPermissionsRequest request,
                    StreamObserver<CheckChannelPermissionsResponse> observer) {
                  observer.onNext(
                      CheckChannelPermissionsResponse.newBuilder()
                          .setServerId(SERVER.toString())
                          .addGranted(Permission.PERMISSION_VIEW_CHANNEL)
                          .addGranted(Permission.PERMISSION_SEND_MESSAGES)
                          .build());
                  observer.onCompleted();
                }
              })
          .build()
          .start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // Dọn queue chính và DLQ để mỗi test bắt đầu từ trạng thái sạch
  @BeforeEach
  void purge() {
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
  }

  // Kịch bản: kênh bị xóa thì gửi tin bị chặn dù gRPC vẫn OK
  // Input: một user gửi tin OK, publish ChannelDeleted của server đó, cùng user gửi lại vào kênh
  // Output: 201 rồi deleted_at khác null, lần gửi lại nhận 404 vì bump phiên bản vô hiệu cache
  @Test
  void channelDeletedMakesSendReturn404() throws Exception {
    UUID channel = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    assertThat(send(user, channel)).isEqualTo(201);

    publish("channel.deleted", UUID.randomUUID(), "ChannelDeleted", ids(SERVER, channel));

    awaitDeleted(channel);
    assertThat(send(user, channel)).isEqualTo(404);
  }

  // Kịch bản: ChannelDeleted đến trước ChannelCreated
  // Input: ChannelDeleted rồi ChannelCreated cùng channelId, sau đó gửi tin
  // Output: deleted_at vẫn khác null và gửi tin trả 404
  @Test
  void channelDeletedBeforeCreatedDoesNotResurrect() throws Exception {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID created = UUID.randomUUID();
    inOrder(
        () -> {
          publish("channel.deleted", UUID.randomUUID(), "ChannelDeleted", ids(server, channel));
          publish("channel.created", created, "ChannelCreated", ids(server, channel));
        });

    awaitProcessed(created);

    assertThat(deleted(channel)).isTrue();
    assertThat(send(UUID.randomUUID(), channel)).isEqualTo(404);
  }

  // Kịch bản: xóa server
  // Input: hai ChannelCreated cùng server rồi ServerDeleted
  // Output: deleted_at của cả hai kênh khác null
  @Test
  void serverDeletedTombstonesEveryChannelOfServer() {
    UUID server = UUID.randomUUID();
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    inOrder(
        () -> {
          publish("channel.created", UUID.randomUUID(), "ChannelCreated", ids(server, c1));
          publish("channel.created", UUID.randomUUID(), "ChannelCreated", ids(server, c2));
          publish("server.deleted", UUID.randomUUID(), "ServerDeleted", Map.of("serverId", server));
        });

    awaitDeleted(c1);
    awaitDeleted(c2);
  }

  // Kịch bản: kênh mới tạo
  // Input: ChannelCreated
  // Output: có dòng channel_refs với server_id đúng và deleted_at null
  @Test
  void channelCreatedCreatesRef() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID created = UUID.randomUUID();

    publish("channel.created", created, "ChannelCreated", ids(server, channel));

    awaitProcessed(created);
    assertThat(
            jdbc.queryForObject(
                "select server_id from messaging.channel_refs where channel_id = ?",
                UUID.class,
                channel))
        .isEqualTo(server);
    assertThat(deleted(channel)).isFalse();
  }

  // Kịch bản: cùng eventId giao hai lần
  // Input: MemberKicked cùng eventId hai lần rồi một event đánh dấu của server khác
  // Output: perm:ver:{server} = "2" (một lần xử lý bump hai lần: trong và sau commit)
  @Test
  void duplicateEventProcessedOnce() {
    UUID server = UUID.randomUUID();
    UUID marker = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    inOrder(
        () -> {
          publish("member.kicked", eventId, "MemberKicked", Map.of("serverId", server));
          publish("member.kicked", eventId, "MemberKicked", Map.of("serverId", server));
          publish("member.left", UUID.randomUUID(), "MemberLeft", Map.of("serverId", marker));
        });

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> redis.opsForValue().get("perm:ver:" + marker) != null);

    assertThat(redis.opsForValue().get("perm:ver:" + server)).isEqualTo("2");
  }

  // Kịch bản: bump lỗi lần đầu
  // Input: MemberKicked, PermissionVersion.bump ném lỗi lần đầu rồi chạy thật
  // Output: processed_event đúng 1 dòng, bump được gọi 2 lần và perm:ver = "2"
  @Test
  void bumpFailureRollsBackInboxAndIsRetried() {
    UUID server = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    doThrow(new IllegalStateException("redis down"))
        .doCallRealMethod()
        .when(permissionVersion)
        .bump(server);

    publish("member.kicked", eventId, "MemberKicked", Map.of("serverId", server));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "2".equals(redis.opsForValue().get("perm:ver:" + server)));
    assertThat(processedCount(eventId)).isEqualTo(1);
    verify(permissionVersion, times(2)).bump(server);
  }

  // Kịch bản: payload thiếu serverId
  // Input: MemberLeft với payload rỗng
  // Output: trong 2 giây DLQ có 1 message, queue chính rỗng, không có dòng processed_event
  @Test
  void missingServerIdGoesToDlqImmediately() {
    UUID eventId = UUID.randomUUID();

    publish("member.left", eventId, "MemberLeft", Map.of());

    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE + ".dlq") == 1);
    assertThat(count(QUEUE)).isZero();
    assertThat(processedCount(eventId)).isZero();
  }

  // Kịch bản: eventType lạ
  // Input: eventType Whatever với routing key member.left
  // Output: queue và DLQ rỗng, processed_event có 1 dòng
  @Test
  void unknownEventTypeIsAckedAndIgnored() {
    UUID eventId = UUID.randomUUID();

    publish("member.left", eventId, "Whatever", Map.of("serverId", UUID.randomUUID()));

    awaitProcessed(eventId);
    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE) == 0);
    assertThat(count(QUEUE + ".dlq")).isZero();
    assertThat(processedCount(eventId)).isEqualTo(1);
  }

  // Payload chứa serverId và channelId
  private static Map<String, Object> ids(UUID server, UUID channel) {
    return Map.of("serverId", server, "channelId", channel);
  }

  // Chạy các lần publish trên cùng một channel AMQP để broker nhận đúng thứ tự
  private void inOrder(Runnable publishes) {
    rabbit.invoke(
        operations -> {
          publishes.run();
          return null;
        });
  }

  // Gửi envelope thẳng vào exchange community.events với routing key cho trước
  private void publish(String routingKey, UUID eventId, String type, Map<String, Object> payload) {
    Map<String, Object> envelope =
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
            "community-service",
            "aggregateType",
            "Test",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        "community.events",
        routingKey,
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Số message sẵn sàng trong queue
  private long count(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }

  // Số dòng inbox của consumer này cho eventId
  private int processedCount(UUID eventId) {
    return jdbc.queryForObject(
        "select count(*) from messaging.processed_event where event_id = ? and consumer = ?",
        Integer.class,
        eventId,
        QUEUE);
  }

  // Chờ tới khi event đã được commit vào inbox
  private void awaitProcessed(UUID eventId) {
    await().atMost(Duration.ofSeconds(10)).until(() -> processedCount(eventId) == 1);
  }

  // Chờ tới khi deleted_at của kênh khác null
  private void awaitDeleted(UUID channel) {
    await().atMost(Duration.ofSeconds(10)).until(() -> deleted(channel));
  }

  // True nếu kênh có dòng với deleted_at khác null
  private boolean deleted(UUID channel) {
    return jdbc.queryForObject(
            "select count(*) from messaging.channel_refs"
                + " where channel_id = ? and deleted_at is not null",
            Integer.class,
            channel)
        == 1;
  }

  // POST một tin vào kênh bằng user cho trước và trả mã trạng thái
  private int send(UUID user, UUID channel) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/api/v1/channels/" + channel + "/messages"))
                .header("Authorization", bearer(user))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hi\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }
}

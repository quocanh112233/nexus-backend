package com.nexus.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.FakeAccessServer;
import com.nexus.realtime.RealtimeGatewayApplication;
import com.nexus.realtime.application.routing.Frame;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.TestJwt;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// IT fan-out: hai instance gateway cùng JVM nghe queue riêng và giao event cho session cục bộ
class FanOutIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final FakeAccessServer ACCESS = new FakeAccessServer();
  private static final Map<String, Object> RABBIT = new HashMap<>();

  private static ConfigurableApplicationContext a;
  private static ConfigurableApplicationContext b;

  private final UUID channelId = UUID.randomUUID();
  private final UUID serverId = UUID.randomUUID();
  private final List<WebSocketStompClient> clients = new ArrayList<>();

  // Khởi động broker và hai instance gateway với instanceId khác nhau
  @BeforeAll
  static void start() {
    RabbitMqContainer.register((name, value) -> RABBIT.put(name, value.get()));
    a = instance("a-" + UUID.randomUUID());
    b = instance("b-" + UUID.randomUUID());
  }

  // Dừng hai instance
  @AfterAll
  static void stop() {
    a.close();
    b.close();
  }

  // Dừng các client STOMP của test
  @AfterEach
  void stopClients() {
    clients.forEach(WebSocketStompClient::stop);
  }

  // Kịch bản: hai client ở hai instance cùng subscribe một kênh
  // Input: publish MessageCreated vào messaging.events
  // Output: cả hai nhận frame type=MessageCreated với data.messageId đúng và đủ bốn trường
  @Test
  void clientsOnTwoInstancesBothReceiveMessageCreated() throws Exception {
    Client c1 = connect(a);
    Client c2 = connect(b);
    c1.subscribe("/topic/channels/" + channelId);
    c2.subscribe("/topic/channels/" + channelId);
    String messageId = UUID.randomUUID().toString();

    publish(
        "messaging.events",
        "message.created",
        "MessageCreated",
        Map.of("channelId", channelId.toString(), "messageId", messageId));

    for (Client c : List.of(c1, c2)) {
      JsonNode frame = c.next();
      assertThat(frame.get("type").asText()).isEqualTo("MessageCreated");
      assertThat(frame.get("data").get("messageId").asText()).isEqualTo(messageId);
      assertThat(frame.get("eventId").asText()).isNotBlank();
      assertThat(Instant.parse(frame.get("occurredAt").asText())).isNotNull();
      assertThat(frame.size()).isEqualTo(4);
    }
  }

  // Kịch bản: client không subscribe kênh
  // Input: publish MessageCreated của kênh X, client thứ ba chỉ subscribe kênh khác
  // Output: client đã subscribe nhận frame, client kia không nhận gì
  @Test
  void onlySubscribedSessionsReceive() throws Exception {
    Client subscribed = connect(a);
    Client other = connect(a);
    subscribed.subscribe("/topic/channels/" + channelId);
    UUID otherChannel = UUID.randomUUID();
    ACCESS.allowView(other.userId, otherChannel);
    other.subscribe("/topic/channels/" + otherChannel);

    publish(
        "messaging.events",
        "message.created",
        "MessageCreated",
        Map.of("channelId", channelId.toString(), "messageId", "m"));

    assertThat(subscribed.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(other.frames.poll(1, TimeUnit.SECONDS)).isNull();
  }

  // Kịch bản: user bị kick nghe queue riêng
  // Input: publish MemberKicked của user U, U subscribe /user/queue/events ở instance B
  // Output: U nhận frame MemberKicked, user khác ở instance A không nhận
  @Test
  void kickedUserGetsUserQueueEvent() throws Exception {
    Client kicked = connect(b);
    Client bystander = connect(a);
    kicked.subscribe("/user/queue/events");
    bystander.subscribe("/user/queue/events");

    publish(
        "community.events",
        "member.kicked",
        "MemberKicked",
        Map.of(
            "serverId", serverId.toString(),
            "userId", kicked.userId.toString(),
            "kickedBy", UUID.randomUUID().toString()));

    JsonNode frame = kicked.next();
    assertThat(frame.get("type").asText()).isEqualTo("MemberKicked");
    assertThat(frame.get("data").get("userId").asText()).isEqualTo(kicked.userId.toString());
    assertThat(bystander.frames.poll(1, TimeUnit.SECONDS)).isNull();
  }

  // Kịch bản: mỗi instance có queue exclusive riêng, tự mất khi instance dừng
  // Input: dựng instance thứ ba, kiểm queue của ba instance từ một kết nối khác, rồi dừng nó
  // Output: queue bị khóa với kết nối ngoài (405), queue của instance đã dừng biến mất (404)
  @Test
  void queuesAreExclusivePerInstance() throws Exception {
    ConfigurableApplicationContext c = instance("c-" + UUID.randomUUID());
    try {
      String queueC = queueOf(c);
      assertThat(declareStatus(queueOf(a))).isEqualTo(405);
      assertThat(declareStatus(queueOf(b))).isEqualTo(405);
      assertThat(declareStatus(queueC)).isEqualTo(405);

      c.close();

      await().atMost(10, TimeUnit.SECONDS).until(() -> declareStatus(queueC) == 404);
      assertThat(declareStatus(queueOf(a))).isEqualTo(405);
    } finally {
      c.close();
    }
  }

  // Kịch bản: PresenceChanged chỉ giao ba trường cho member server
  // Input: publish PresenceChanged có serverIds và friendIds, member subscribe topic server
  // Output: frame data có đúng userId, status, changedAt
  @Test
  void presenceFrameDataHasNoRecipientLists() throws Exception {
    Client member = connect(a);
    ACCESS.allowMember(member.userId, serverId);
    member.subscribe("/topic/servers/" + serverId);

    publish(
        "presence.events",
        "presence.changed",
        "PresenceChanged",
        Map.of(
            "userId", UUID.randomUUID().toString(),
            "status", "ONLINE",
            "changedAt", Instant.now().toString(),
            "serverIds", List.of(serverId.toString(), UUID.randomUUID().toString()),
            "friendIds", List.of()));

    JsonNode data = member.next().get("data");
    assertThat(data.propertyNames()).containsExactlyInAnyOrder("userId", "status", "changedAt");
  }

  // Kịch bản: MemberBanned không lộ lý do ban cho member server
  // Input: publish MemberBanned có reason và bannedBy, một member server và người bị ban cùng nghe
  // Output: topic server không có reason/bannedBy, queue của người bị ban có đủ
  @Test
  void bannedTopicFrameHidesReasonButUserQueueKeepsIt() throws Exception {
    Client member = connect(a);
    Client banned = connect(b);
    ACCESS.allowMember(member.userId, serverId);
    member.subscribe("/topic/servers/" + serverId);
    banned.subscribe("/user/queue/events");

    publish(
        "community.events",
        "member.banned",
        "MemberBanned",
        Map.of(
            "serverId", serverId.toString(),
            "userId", banned.userId.toString(),
            "bannedBy", UUID.randomUUID().toString(),
            "reason", "spam",
            "wasMember", true));

    JsonNode topicData = member.next().get("data");
    assertThat(topicData.propertyNames())
        .containsExactlyInAnyOrder("serverId", "userId", "wasMember");
    JsonNode userData = banned.next().get("data");
    assertThat(userData.get("reason").asText()).isEqualTo("spam");
    assertThat(userData.has("bannedBy")).isTrue();
  }

  // Kịch bản: event hỏng không làm gateway dừng
  // Input: body không phải JSON, MessageCreated thiếu channelId, rồi MessageCreated hợp lệ
  // Output: hai event đầu bị bỏ, event hợp lệ vẫn tới client
  @Test
  void malformedEventIsDroppedAndGatewayKeepsRunning() throws Exception {
    Client c1 = connect(a);
    c1.subscribe("/topic/channels/" + channelId);
    RabbitTemplate rabbit = a.getBean(RabbitTemplate.class);
    rabbit.send(
        "messaging.events",
        "message.created",
        MessageBuilder.withBody("không phải json".getBytes()).build());
    publish("messaging.events", "message.created", "MessageCreated", Map.of("messageId", "x"));

    publish(
        "messaging.events",
        "message.created",
        "MessageCreated",
        Map.of("channelId", channelId.toString(), "messageId", "ok"));

    assertThat(c1.next().get("data").get("messageId").asText()).isEqualTo("ok");
  }

  // Dựng một instance gateway đầy đủ với cổng ngẫu nhiên, broker của container và gRPC giả
  private static ConfigurableApplicationContext instance(String instanceId) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--app.instance-id=" + instanceId,
                "--server.port=0",
                "--management.server.port=0",
                "--spring.rabbitmq.virtual-host=/",
                "--app.jwt.jwks-uri=" + JWKS.uri(),
                "--app.grpc.service-token=dummy-realtime",
                "--app.grpc.community.target=" + ACCESS.target(),
                "--app.grpc.messaging.target=" + ACCESS.target()));
    RABBIT.forEach((name, value) -> args.add("--" + name + "=" + value));
    return SpringApplication.run(RealtimeGatewayApplication.class, args.toArray(String[]::new));
  }

  // Tên queue riêng của instance
  private static String queueOf(ConfigurableApplicationContext ctx) {
    return ctx.getBean(org.springframework.amqp.core.Queue.class).getName();
  }

  // Khai báo passive queue từ một kết nối khác, trả mã lỗi của broker (0 nếu khai báo được)
  private static int declareStatus(String queue) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost((String) RABBIT.get("spring.rabbitmq.host"));
    factory.setPort((Integer) RABBIT.get("spring.rabbitmq.port"));
    factory.setUsername((String) RABBIT.get("spring.rabbitmq.username"));
    factory.setPassword((String) RABBIT.get("spring.rabbitmq.password"));
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.queueDeclarePassive(queue);
      return 0;
    } catch (IOException e) {
      return ((AMQP.Channel.Close) ((ShutdownSignalException) e.getCause()).getReason())
          .getReplyCode();
    }
  }

  // Publish envelope JSON hợp lệ vào exchange qua instance A
  private void publish(String exchange, String key, String type, Map<String, Object> payload) {
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("eventId", UUID.randomUUID().toString());
    envelope.put("eventType", type);
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", Instant.now().toString());
    envelope.put("producer", "it");
    envelope.put("aggregateType", "Agg");
    envelope.put("aggregateId", UUID.randomUUID().toString());
    envelope.put("payload", payload);
    a.getBean(RabbitTemplate.class)
        .send(exchange, key, MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope)).build());
  }

  // Kết nối một client STOMP mới của user ngẫu nhiên tới instance
  private Client connect(ConfigurableApplicationContext ctx) throws Exception {
    UUID userId = UUID.randomUUID();
    ACCESS.allowView(userId, channelId);
    WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    clients.add(stomp);
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + JWT.token(userId, "user"));
    StompSession session =
        stomp
            .connectAsync(
                "ws://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/ws",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);
    return new Client(
        ctx.getBean(SubscriptionRegistry.class),
        ctx.getBean(SimpMessagingTemplate.class),
        userId,
        session);
  }

  // Client STOMP gom frame JSON nhận được vào hàng đợi, frame Probe chỉ dùng để biết đã nghe được
  private static final class Client {

    final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    final UUID userId;
    private final SubscriptionRegistry registry;
    private final SimpMessagingTemplate template;
    private final StompSession session;
    private volatile boolean probed;

    // Gói registry, template gửi STOMP của instance, user và session đã kết nối
    Client(
        SubscriptionRegistry registry,
        SimpMessagingTemplate template,
        UUID userId,
        StompSession session) {
      this.registry = registry;
      this.template = template;
      this.userId = userId;
      this.session = session;
    }

    // Subscribe rồi gửi frame Probe lặp lại tới khi client nhận được, chứng tỏ broker đã đăng ký
    void subscribe(String destination) throws Exception {
      session.subscribe(
          destination,
          new StompFrameHandler() {
            // Nhận payload thô để tự parse JSON
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return byte[].class;
            }

            // Frame Probe chỉ bật cờ, frame khác xếp vào hàng đợi
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
              JsonNode frame = MAPPER.readTree((byte[]) payload);
              if ("Probe".equals(frame.get("type").asText())) {
                probed = true;
              } else {
                frames.add(frame);
              }
            }
          });
      Frame probe = new Frame("Probe", "p", "t", Map.of());
      probed = false;
      await()
          .atMost(5, TimeUnit.SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .until(
              () -> {
                if (destination.startsWith("/user/")) {
                  template.convertAndSendToUser(userId.toString(), "/queue/events", probe);
                } else {
                  template.convertAndSend(destination, probe);
                }
                return probed;
              });
    }

    // Frame kế tiếp, tối đa 5 giây
    JsonNode next() throws Exception {
      JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
      assertThat(frame).as("frame nhận được").isNotNull();
      return frame;
    }
  }
}

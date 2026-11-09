package com.nexus.realtime.infrastructure.stomp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.FakeAccessServer;
import com.nexus.realtime.RealtimeGatewayApplication;
import com.nexus.realtime.application.routing.Frame;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.TestJwt;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// IT thu hồi subscription với hai instance gateway, RabbitMQ thật và gRPC giả đổi được
class RevocationIT {

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

  // Xóa cấu hình gRPC giả để test trước không ảnh hưởng test sau
  @BeforeEach
  void resetFake() {
    ACCESS.reset();
  }

  // Dừng các client STOMP của test
  @AfterEach
  void stopClients() {
    clients.forEach(WebSocketStompClient::stop);
  }

  // Kịch bản: member bị ban khi đang nghe topic server và topic kênh
  // Input: fake vẫn cho phép (không cần gRPC), publish MemberBanned rồi MessageCreated của kênh
  // Output: trong 1 giây nhận hai SubscriptionRevoked lý do MemberBanned, không nhận MessageCreated
  @Test
  void memberBannedStopsReceivingWithinOneSecond() throws Exception {
    Client banned = connect(a);
    Client bystander = connect(a);
    bystander.subscribe("/topic/channels/" + channelId);
    banned.subscribe("/user/queue/events");
    banned.subscribe("/topic/servers/" + serverId);
    banned.subscribe("/topic/channels/" + channelId);

    publishBan(banned.userId);

    Set<String> revoked = new HashSet<>();
    await()
        .atMost(Duration.ofSeconds(1))
        .until(
            () -> {
              JsonNode frame = banned.frames.poll(50, TimeUnit.MILLISECONDS);
              if (frame != null && "SubscriptionRevoked".equals(frame.get("type").asText())) {
                assertThat(frame.size()).isEqualTo(4);
                assertThat(frame.get("data").propertyNames())
                    .containsExactlyInAnyOrder("destination", "reason");
                assertThat(frame.get("data").get("reason").asText()).isEqualTo("MemberBanned");
                revoked.add(frame.get("data").get("destination").asText());
              }
              return revoked.size() == 2;
            });
    assertThat(revoked)
        .containsExactlyInAnyOrder("/topic/servers/" + serverId, "/topic/channels/" + channelId);
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> brokerSubscribers(a, "/topic/channels/" + channelId) == 1);
    publishMessage("after-ban");
    assertThat(bystander.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(banned.drainTypes(500)).doesNotContain("MessageCreated");
  }

  // Kịch bản: re-check chậm 300 ms, MessageCreated đến ngay sau MemberRolesChanged
  // Input: fake chậm và từ chối kênh của user, publish MemberRolesChanged rồi MessageCreated liền
  // Output: người còn quyền nhận MessageCreated, người mất quyền không nhận frame nào của nó
  @Test
  void noFrameLeaksDuringRecheck() throws Exception {
    Client kicked = connect(a);
    Client member = connect(a);
    member.subscribe("/topic/channels/" + channelId);
    kicked.subscribe("/user/queue/events");
    kicked.subscribe("/topic/servers/" + serverId);
    kicked.subscribe("/topic/channels/" + channelId);
    ACCESS.denyView(kicked.userId, channelId);
    ACCESS.delay(300);

    publishRoles(kicked.userId);
    publishMessage("right-after-roles");

    assertThat(member.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(kicked.drainTypes(500)).doesNotContain("MessageCreated");
  }

  // Kịch bản: access token của session đã hết hạn nhưng gateway vẫn re-check bằng service token
  // Input: token sống 2 giây, đợi hết hạn, MemberRolesChanged (còn quyền) rồi mất quyền và lặp lại
  // Output: còn quyền thì giữ subscription, mất quyền thì thu hồi, call dùng service token
  @Test
  void expiredAccessTokenDoesNotBreakRecheck() throws Exception {
    Instant exp = Instant.now().plusSeconds(2);
    Client client = connect(a, exp);
    client.subscribe("/user/queue/events");
    client.subscribe("/topic/channels/" + channelId);
    await().atMost(Duration.ofSeconds(5)).until(() -> Instant.now().isAfter(exp));

    publishRoles(client.userId);
    publishMessage("still-allowed");
    assertThat(client.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(ACCESS.lastToken()).isEqualTo("dummy-realtime");

    ACCESS.denyView(client.userId, channelId);
    publishRoles(client.userId);
    assertThat(client.awaitType("SubscriptionRevoked").get("data").get("destination").asText())
        .isEqualTo("/topic/channels/" + channelId);
  }

  // Kịch bản: user bị kick có session ở cả hai instance, một người khác chỉ ở instance B
  // Input: publish MemberKicked một lần (thu hồi không cần gRPC), rồi publish MessageCreated
  // Output: mỗi instance thu hồi session của mình, người khác vẫn nhận MessageCreated
  @Test
  void revocationHitsOwnSessionsOnEachInstance() throws Exception {
    Client onA = connect(a);
    Client kickedOnB = connect(b, onA.userId);
    Client bystander = connect(b);
    for (Client c : List.of(onA, kickedOnB)) {
      c.subscribe("/user/queue/events");
      c.subscribe("/topic/channels/" + channelId);
    }
    bystander.subscribe("/topic/channels/" + channelId);

    publish(
        "community.events",
        "member.kicked",
        "MemberKicked",
        Map.of(
            "serverId", serverId.toString(),
            "userId", onA.userId.toString(),
            "kickedBy", UUID.randomUUID().toString()));
    for (Client c : List.of(onA, kickedOnB)) {
      assertThat(c.awaitType("SubscriptionRevoked").get("data").get("destination").asText())
          .isEqualTo("/topic/channels/" + channelId);
    }
    publishMessage("after-kick");

    assertThat(bystander.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(onA.drainTypes(500)).doesNotContain("MessageCreated");
    assertThat(kickedOnB.drainTypes(500)).doesNotContain("MessageCreated");
  }

  // Kịch bản: bộ lọc outbound không được chặn nhầm frame hợp lệ
  // Input: một client nghe topic kênh, topic server và user queue rồi nhận ba event tương ứng
  // Output: nhận đủ MessageCreated, ServerUpdated và ServerCreated
  @Test
  void normalDeliveryStillWorksForChannelServerAndUserQueue() throws Exception {
    Client c = connect(a);
    c.subscribe("/topic/channels/" + channelId);
    c.subscribe("/topic/servers/" + serverId);
    c.subscribe("/user/queue/events");

    publishMessage("m");
    publish(
        "community.events",
        "server.updated",
        "ServerUpdated",
        Map.of("serverId", serverId.toString()));
    publish(
        "community.events",
        "server.created",
        "ServerCreated",
        Map.of("serverId", serverId.toString(), "ownerId", c.userId.toString()));

    assertThat(List.of(c.next(), c.next(), c.next()))
        .extracting(f -> f.get("type").asText())
        .containsExactlyInAnyOrder("MessageCreated", "ServerUpdated", "ServerCreated");
  }

  // Kịch bản: subscription bị xóa khỏi sổ mà không gửi UNSUBSCRIBE nào
  // Input: xóa thẳng khỏi registry của instance A rồi publish MessageCreated của kênh
  // Output: client không nhận gì (chỉ bộ lọc outbound chặn), người khác vẫn nhận
  @Test
  void outboundFilterAloneBlocksFrameAfterRegistryRemoval() throws Exception {
    Client removed = connect(a);
    Client bystander = connect(a);
    removed.subscribe("/topic/channels/" + channelId);
    bystander.subscribe("/topic/channels/" + channelId);
    SubscriptionRegistry registry = a.getBean(SubscriptionRegistry.class);
    registry
        .byUser(removed.userId)
        .forEach(s -> registry.remove(s.sessionId(), s.subscriptionId()));

    publishMessage("filtered");

    assertThat(bystander.next().get("type").asText()).isEqualTo("MessageCreated");
    assertThat(removed.drainTypes(500)).isEmpty();
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

  // Số subscription trong simple broker của instance tới destination (kiểm UNSUBSCRIBE giả)
  private static int brokerSubscribers(ConfigurableApplicationContext ctx, String destination) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setDestination(destination);
    return ctx.getBean(SimpleBrokerMessageHandler.class)
        .getSubscriptionRegistry()
        .findSubscriptions(
            org.springframework.messaging.support.MessageBuilder.createMessage(
                new byte[0], headers.getMessageHeaders()))
        .size();
  }

  // Publish MemberBanned của user vào server hiện tại
  private void publishBan(UUID userId) {
    publish(
        "community.events",
        "member.banned",
        "MemberBanned",
        Map.of(
            "serverId", serverId.toString(),
            "userId", userId.toString(),
            "bannedBy", UUID.randomUUID().toString(),
            "wasMember", true));
  }

  // Publish MemberRolesChanged của user vào server hiện tại
  private void publishRoles(UUID userId) {
    publish(
        "community.events",
        "member.roles.changed",
        "MemberRolesChanged",
        Map.of("serverId", serverId.toString(), "userId", userId.toString()));
  }

  // Publish MessageCreated vào kênh hiện tại
  private void publishMessage(String messageId) {
    publish(
        "messaging.events",
        "message.created",
        "MessageCreated",
        Map.of("channelId", channelId.toString(), "messageId", messageId));
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

  // Kết nối client của user ngẫu nhiên, token sống 15 phút
  private Client connect(ConfigurableApplicationContext ctx) throws Exception {
    return connect(ctx, UUID.randomUUID(), Instant.now().plusSeconds(900));
  }

  // Kết nối thêm một session của user đã có
  private Client connect(ConfigurableApplicationContext ctx, UUID userId) throws Exception {
    return connect(ctx, userId, Instant.now().plusSeconds(900));
  }

  // Kết nối client user ngẫu nhiên với token hết hạn vào thời điểm cho trước
  private Client connect(ConfigurableApplicationContext ctx, Instant exp) throws Exception {
    return connect(ctx, UUID.randomUUID(), exp);
  }

  // Kết nối một client STOMP của user tới instance, user được xem kênh và là member server
  private Client connect(ConfigurableApplicationContext ctx, UUID userId, Instant exp)
      throws Exception {
    ACCESS.allowView(userId, channelId, serverId);
    ACCESS.allowMember(userId, serverId);
    WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    clients.add(stomp);
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + JWT.token(userId, "user", exp));
    StompSession session =
        stomp
            .connectAsync(
                "ws://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/ws",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);
    return new Client(ctx.getBean(SimpMessagingTemplate.class), userId, session);
  }

  // Client STOMP gom frame JSON vào hàng đợi, frame Probe chỉ dùng để biết đã nghe được
  private static final class Client {

    final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    final UUID userId;
    private final SimpMessagingTemplate template;
    private final StompSession session;
    private volatile boolean probed;

    // Gói template gửi STOMP của instance, user và session đã kết nối
    Client(SimpMessagingTemplate template, UUID userId, StompSession session) {
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

    // Frame kế tiếp có type cho trước, bỏ qua frame khác, tối đa 5 giây
    JsonNode awaitType(String type) throws Exception {
      for (JsonNode frame = next(); ; frame = next()) {
        if (type.equals(frame.get("type").asText())) {
          return frame;
        }
      }
    }

    // Type của mọi frame đến trong khoảng thời gian cho trước
    List<String> drainTypes(long millis) throws Exception {
      List<String> types = new ArrayList<>();
      long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      JsonNode frame;
      while ((frame = frames.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS)) != null) {
        types.add(frame.get("type").asText());
      }
      return types;
    }
  }
}

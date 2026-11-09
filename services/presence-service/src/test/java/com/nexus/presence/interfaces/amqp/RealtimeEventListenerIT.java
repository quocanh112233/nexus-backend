package com.nexus.presence.interfaces.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.PresenceChangeTap;
import com.nexus.presence.domain.relation.RelationshipProjection;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.RedisContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra listener realtime.events với RabbitMQ và Redis thật, gRPC Community trỏ vào cổng chết
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.grpc.service-token=test-token",
      "app.grpc.community.target=localhost:1"
    })
class RealtimeEventListenerIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String QUEUE = "presence.realtime.events";

  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private RelationshipProjection projection;
  @Autowired private StringRedisTemplate redis;

  private PresenceChangeTap tap;
  private final UUID user = UUID.randomUUID();
  private final String inst = "gw-" + UUID.randomUUID();

  // Trỏ broker (vhost "/") và Redis tới container
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    RedisContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Dọn queue chính, DLQ và dựng queue tạm nhận PresenceChanged
  @BeforeEach
  void setUp() {
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
    tap = new PresenceChangeTap(admin, rabbit);
  }

  // Xóa queue tạm
  @AfterEach
  void tearDown() {
    tap.close();
  }

  // Kịch bản: user là member của một server mở session đầu tiên
  // Input: nạp projection {server}, publish session.connected
  // Output: PresenceChanged ONLINE từ presence-service có serverIds {server}, friendIds rỗng
  @Test
  void sessionConnectedPublishesOnlineToPresenceEvents() {
    UUID server = UUID.randomUUID();
    projection.load(user, Set.of(server), projection.revision(user));

    connected("s1");

    JsonNode event = tap.awaitStatus(user, "ONLINE");
    assertThat(event.get("eventType").asText()).isEqualTo("PresenceChanged");
    assertThat(event.get("producer").asText()).isEqualTo("presence-service");
    JsonNode payload = event.get("payload");
    assertThat(payload.get("serverIds").get(0).asText()).isEqualTo(server.toString());
    assertThat(payload.get("friendIds")).isEmpty();
    assertThat(payload.has("changedAt")).isTrue();
  }

  // Kịch bản: user có hai thiết bị
  // Input: connect s1, s2, disconnect s1 rồi (sau khi kiểm) disconnect s2
  // Output: chỉ ONLINE sau ba event đầu, OFFLINE chỉ xuất hiện sau khi s2 đóng
  @Test
  void twoDevicesOfflineOnlyWhenLastCloses() {
    connected("s1");
    connected("s2");
    await().atMost(Duration.ofSeconds(5)).until(() -> redis.hasKey("presence:session:s2" + inst));
    disconnected("s1");
    flush();
    assertThat(tap.statuses(user)).containsExactly("ONLINE");

    disconnected("s2");

    tap.awaitStatus(user, "OFFLINE");
  }

  // Kịch bản: SessionDisconnected bị giao trùng
  // Input: connect, disconnect hai lần
  // Output: đúng ONLINE rồi OFFLINE một lần
  @Test
  void duplicateSessionDisconnectedIsNoOp() {
    connected("s1");
    disconnected("s1");
    disconnected("s1");
    flush();

    assertThat(tap.statuses(user)).containsExactly("ONLINE", "OFFLINE");
  }

  // Kịch bản: SessionConnected bị giao trùng
  // Input: connect cùng session hai lần
  // Output: đúng một ONLINE
  @Test
  void duplicateSessionConnectedIsNoOp() {
    connected("s1");
    connected("s1");
    flush();

    assertThat(tap.statuses(user)).containsExactly("ONLINE");
  }

  // Kịch bản: SessionDisconnected đến trước SessionConnected
  // Input: disconnect rồi connect cùng session
  // Output: không có PresenceChanged nào và không có status ONLINE trong Redis
  @Test
  void disconnectBeforeConnectNeverGoesOnline() {
    disconnected("s1");
    connected("s1");
    flush();

    assertThat(tap.statuses(user)).isEmpty();
    assertThat(redis.hasKey("presence:status:" + user)).isFalse();
  }

  // Kịch bản: SessionConnected bị mất, heartbeat bù, sau đó heartbeat không còn session
  // Input: heartbeat có s1 và userId, rồi heartbeat rỗng sau 10 giây
  // Output: ONLINE rồi OFFLINE
  @Test
  void heartbeatRepairsLostEvents() {
    heartbeat(List.of("s1"), List.of(user.toString()), Instant.now());
    tap.awaitStatus(user, "ONLINE");

    heartbeat(List.of(), List.of(), Instant.now().plusSeconds(10));

    tap.awaitStatus(user, "OFFLINE");
  }

  // Kịch bản: payload session.connected thiếu userId
  // Input: SessionConnected chỉ có sessionId
  // Output: trong 2 giây DLQ có 1 message và queue chính rỗng (không retry)
  @Test
  void malformedSessionEventGoesToDlq() {
    publish("session.connected", "SessionConnected", Map.of("sessionId", "s1"));

    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE + ".dlq") == 1);
    assertThat(count(QUEUE)).isZero();
  }

  // Kịch bản: eventType lạ trên routing key session
  // Input: Whatever với payload rỗng rồi event mốc hợp lệ
  // Output: event mốc được xử lý, DLQ rỗng
  @Test
  void unknownEventTypeAckedAndIgnored() {
    publish("session.connected", "Whatever", Map.of());

    flush();

    assertThat(count(QUEUE + ".dlq")).isZero();
  }

  // Gửi event mốc của user khác rồi chờ ONLINE của nó để chắc các event trước đã xử lý xong
  private void flush() {
    UUID marker = UUID.randomUUID();
    publish(
        "session.connected",
        "SessionConnected",
        Map.of(
            "userId",
            marker.toString(),
            "sessionId",
            "m-" + marker,
            "instanceId",
            inst,
            "connectedAt",
            Instant.now().toString()));
    tap.awaitStatus(marker, "ONLINE");
  }

  // Publish SessionConnected của user test
  private void connected(String sessionId) {
    publish(
        "session.connected",
        "SessionConnected",
        Map.of(
            "userId",
            user.toString(),
            "sessionId",
            sessionId + inst,
            "instanceId",
            inst,
            "connectedAt",
            Instant.now().toString()));
  }

  // Publish SessionDisconnected của user test
  private void disconnected(String sessionId) {
    publish(
        "session.disconnected",
        "SessionDisconnected",
        Map.of("userId", user.toString(), "sessionId", sessionId + inst, "instanceId", inst));
  }

  // Publish GatewayHeartbeat của instance test
  private void heartbeat(List<String> sessionIds, List<String> userIds, Instant sentAt) {
    List<String> ids = sessionIds.stream().map(s -> s + inst).toList();
    publish(
        "gateway.heartbeat",
        "GatewayHeartbeat",
        Map.of(
            "instanceId",
            inst,
            "sessionIds",
            ids,
            "userIds",
            userIds,
            "sentAt",
            sentAt.toString()));
  }

  // Gửi envelope vào exchange realtime.events
  private void publish(String routingKey, String type, Map<String, Object> payload) {
    UUID eventId = UUID.randomUUID();
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
            "realtime-gateway",
            "aggregateType",
            "Session",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        "realtime.events",
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
}

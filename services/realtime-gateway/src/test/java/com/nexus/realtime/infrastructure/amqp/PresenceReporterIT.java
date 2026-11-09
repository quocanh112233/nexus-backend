package com.nexus.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.GatewayHarness;
import com.nexus.realtime.GatewayHarness.Client;
import com.nexus.realtime.GatewayHarness.Tap;
import com.nexus.testsupport.RabbitMqContainer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

// IT báo session cho Presence: kiểm event session.* và gateway.heartbeat trên queue tạm
class PresenceReporterIT {

  private static final GatewayHarness HARNESS = new GatewayHarness();
  private static ConfigurableApplicationContext gateway;
  private static String instanceId;

  // Khởi động một instance heartbeat 300 ms
  @BeforeAll
  static void start() {
    instanceId = "p-" + UUID.randomUUID();
    gateway = HARNESS.instance(instanceId, "--app.presence.heartbeat-interval=300ms");
  }

  // Dừng client và instance
  @AfterAll
  static void stop() {
    HARNESS.close();
    gateway.close();
  }

  // Kịch bản: client kết nối
  // Input: CONNECT của user U vào instance
  // Output: nhận SessionConnected đủ trường, persistent, message_id bằng eventId
  @Test
  void connectPublishesSessionConnected() throws Exception {
    Tap tap = new Tap(gateway, "session.*", "gateway.heartbeat");
    UUID userId = UUID.randomUUID();

    HARNESS.connect(gateway, userId);

    JsonNode event = awaitEvent(tap, "SessionConnected", userId);
    assertThat(event.get("producer").asText()).isEqualTo("realtime-gateway");
    assertThat(event.get("payload").propertyNames())
        .containsExactlyInAnyOrder("userId", "sessionId", "instanceId", "connectedAt");
    assertThat(event.get("payload").get("instanceId").asText()).isEqualTo(instanceId);
    var props =
        tap.messages.stream()
            .filter(
                m -> event.get("eventId").asText().equals(m.getMessageProperties().getMessageId()))
            .findFirst()
            .orElseThrow()
            .getMessageProperties();
    assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    assertThat(props.getMessageId()).isEqualTo(event.get("eventId").asText());
  }

  // Kịch bản: client ngắt kết nối êm, event không được lặp dù có hai tín hiệu đóng
  // Input: CONNECT rồi DISCONNECT, chờ heartbeat tiếp theo không còn session
  // Output: đúng một SessionDisconnected khớp sessionId của SessionConnected
  @Test
  void disconnectPublishesSessionDisconnected() throws Exception {
    Tap tap = new Tap(gateway, "session.*", "gateway.heartbeat");
    UUID userId = UUID.randomUUID();
    Client client = HARNESS.connect(gateway, userId);
    String sessionId =
        awaitEvent(tap, "SessionConnected", userId).get("payload").get("sessionId").asText();

    client.session.disconnect();

    awaitEvent(tap, "SessionDisconnected", userId);
    int heartbeats = tap.events("GatewayHeartbeat").size();
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> tap.events("GatewayHeartbeat").size() > heartbeats + 1);
    List<JsonNode> closed =
        tap.events("SessionDisconnected").stream()
            .filter(e -> sessionId.equals(e.get("payload").get("sessionId").asText()))
            .toList();
    assertThat(closed).hasSize(1);
  }

  // Kịch bản: heartbeat định kỳ liệt kê session đang mở
  // Input: hai client kết nối, interval 300 ms
  // Output: có heartbeat của instance chứa đúng sessionId của cả hai, kèm sentAt
  @Test
  void heartbeatEveryThirtySecondsListsOpenSessions() throws Exception {
    Tap tap = new Tap(gateway, "session.*", "gateway.heartbeat");
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    HARNESS.connect(gateway, u1);
    HARNESS.connect(gateway, u2);
    String s1 = awaitEvent(tap, "SessionConnected", u1).get("payload").get("sessionId").asText();
    String s2 = awaitEvent(tap, "SessionConnected", u2).get("payload").get("sessionId").asText();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                tap.events("GatewayHeartbeat").stream()
                    .map(e -> e.get("payload"))
                    .anyMatch(
                        p ->
                            instanceId.equals(p.get("instanceId").asText())
                                && p.get("sessionIds").toString().contains(s1)
                                && p.get("sessionIds").toString().contains(s2)
                                && p.has("sentAt")));
  }

  // Kịch bản: instance dừng graceful khi còn session mở
  // Input: instance riêng có hai client, đóng context
  // Output: SessionDisconnected cho cả hai sessionId, đúng instanceId
  @Test
  void gracefulShutdownDisconnectsAllSessions() throws Exception {
    String id = "g-" + UUID.randomUUID();
    ConfigurableApplicationContext victim = HARNESS.instance(id);
    Tap tap = new Tap(gateway, "session.*");
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    HARNESS.connect(victim, u1);
    HARNESS.connect(victim, u2);
    awaitEvent(tap, "SessionConnected", u1);
    awaitEvent(tap, "SessionConnected", u2);

    victim.close();

    JsonNode d1 = awaitEvent(tap, "SessionDisconnected", u1);
    JsonNode d2 = awaitEvent(tap, "SessionDisconnected", u2);
    assertThat(d1.get("payload").get("instanceId").asText()).isEqualTo(id);
    assertThat(d2.get("payload").get("instanceId").asText()).isEqualTo(id);
  }

  // Kịch bản: broker treo khi có client kết nối
  // Input: pause broker rồi CONNECT thêm một client, sau đó unpause
  // Output: cả hai socket vẫn mở trước và sau khi broker chạy lại
  @Test
  void publishFailureDoesNotCloseSocket() throws Exception {
    Client first = HARNESS.connect(gateway, UUID.randomUUID());
    RabbitMqContainer.pause();
    try {
      Client second = HARNESS.connect(gateway, UUID.randomUUID());
      assertThat(first.session.isConnected()).isTrue();
      assertThat(second.session.isConnected()).isTrue();
    } finally {
      RabbitMqContainer.unpause();
    }
    assertThat(first.session.isConnected()).isTrue();
  }

  // Chờ event theo loại của user, trả envelope khớp đầu tiên
  private static JsonNode awaitEvent(Tap tap, String type, UUID userId) {
    JsonNode[] found = new JsonNode[1];
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () -> {
              found[0] =
                  tap.events(type).stream()
                      .filter(
                          e -> userId.toString().equals(e.get("payload").get("userId").asText()))
                      .findFirst()
                      .orElse(null);
              return found[0] != null;
            });
    return found[0];
  }
}

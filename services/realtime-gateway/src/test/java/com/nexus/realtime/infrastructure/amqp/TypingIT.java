package com.nexus.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.realtime.GatewayHarness;
import com.nexus.realtime.GatewayHarness.Client;
import com.nexus.realtime.GatewayHarness.Tap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

// IT typing: hai instance gateway cùng JVM, SEND typing tới TypingStarted rồi tới client khác
class TypingIT {

  private static final GatewayHarness HARNESS = new GatewayHarness();
  private static ConfigurableApplicationContext a;
  private static ConfigurableApplicationContext b;

  private final UUID channelId = UUID.randomUUID();

  // Khởi động hai instance với instanceId khác nhau
  @BeforeAll
  static void start() {
    a = HARNESS.instance("a-" + UUID.randomUUID());
    b = HARNESS.instance("b-" + UUID.randomUUID());
  }

  // Dừng client và hai instance
  @AfterAll
  static void stop() {
    HARNESS.close();
    a.close();
    b.close();
  }

  // Kịch bản: A gõ trong kênh, B ở instance khác cùng subscribe kênh
  // Input: SEND /app/channels/{id}/typing từ A (instance A)
  // Output: B (instance B) nhận frame TypingStarted với data.userId là A
  @Test
  void typingReachesClientOnOtherInstance() throws Exception {
    Client typist = subscribed(a);
    Client watcher = subscribed(b);

    typist.session.send("/app/channels/" + channelId + "/typing", new byte[0]);

    JsonNode frame = watcher.next();
    assertThat(frame.get("type").asText()).isEqualTo("TypingStarted");
    assertThat(frame.get("data").get("userId").asText()).isEqualTo(typist.userId.toString());
    assertThat(frame.get("data").get("channelId").asText()).isEqualTo(channelId.toString());
  }

  // Kịch bản: user chưa subscribe kênh thì không được gõ vào kênh đó
  // Input: user lạ SEND typing trước, rồi user hợp lệ SEND typing
  // Output: watcher chỉ nhận một TypingStarted, của user hợp lệ
  @Test
  void unsubscribedUserCannotTypeIntoChannel() throws Exception {
    Client watcher = subscribed(b);
    Client typist = subscribed(a);
    Client stranger = HARNESS.connect(a, UUID.randomUUID());

    stranger.session.send("/app/channels/" + channelId + "/typing", new byte[0]);
    typist.session.send("/app/channels/" + channelId + "/typing", new byte[0]);

    assertThat(watcher.next().get("data").get("userId").asText())
        .isEqualTo(typist.userId.toString());
    assertThat(watcher.frames.poll(1, TimeUnit.SECONDS)).isNull();
  }

  // Kịch bản: không ai nhận message typing trong queue
  // Input: một TypingStarted vào queue tạm bind typing.started, không consumer
  // Output: queue có 1 message rồi tự về 0 sau khoảng 5 giây
  @Test
  void typingMessageExpiresAfterFiveSeconds() throws Exception {
    Tap tap = new Tap(a, "typing.started");
    Client typist = subscribed(a);

    typist.session.send("/app/channels/" + channelId + "/typing", new byte[0]);

    RabbitAdmin admin = a.getBean(RabbitAdmin.class);
    await().atMost(5, TimeUnit.SECONDS).until(() -> count(admin, tap) == 1);
    await().atMost(15, TimeUnit.SECONDS).until(() -> count(admin, tap) == 0);
  }

  // Kịch bản: TypingStarted là event tạm, không cần bền
  // Input: một TypingStarted vào queue tạm bind typing.started
  // Output: message non-persistent, expiration 5000 ms, message_id bằng eventId
  @Test
  void typingIsNotPersistent() throws Exception {
    Tap tap = new Tap(a, "typing.started");
    Client typist = subscribed(a);

    typist.session.send("/app/channels/" + channelId + "/typing", new byte[0]);

    await().atMost(5, TimeUnit.SECONDS).until(() -> !tap.events("TypingStarted").isEmpty());
    var props = tap.messages.get(0).getMessageProperties();
    assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
    assertThat(props.getExpiration()).isEqualTo("5000");
    assertThat(props.getMessageId())
        .isEqualTo(tap.events("TypingStarted").get(0).get("eventId").asText());
  }

  // Số message đang chờ trong queue tạm
  private static long count(RabbitAdmin admin, Tap tap) {
    return admin.getQueueInfo(tap.queue).getMessageCount();
  }

  // Kết nối user mới và subscribe kênh của test
  private Client subscribed(ConfigurableApplicationContext ctx) throws Exception {
    UUID userId = UUID.randomUUID();
    GatewayHarness.ACCESS.allowView(userId, channelId);
    Client client = HARNESS.connect(ctx, userId);
    client.subscribe("/topic/channels/" + channelId);
    return client;
  }
}

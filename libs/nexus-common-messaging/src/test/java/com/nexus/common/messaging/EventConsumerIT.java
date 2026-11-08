package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.consumerapp.probe.ProbeListener;
import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra consumer: đọc envelope, inbox, retry và DLQ với Postgres và RabbitMQ thật
@SpringBootTest(
    classes = ConsumerApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.application.name=it-consumer",
      "it.probe.enabled=true",
      "spring.rabbitmq.connection-timeout=2s",
      "spring.rabbitmq.channel-rpc-timeout=2s"
    })
@DirtiesContext
class EventConsumerIT extends PostgresIntegrationTest {

  @Autowired private ProbeListener probe;
  @Autowired private InboxGuard inbox;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitListenerEndpointRegistry registry;
  @Autowired private JsonMapper mapper;

  // Đẩy cấu hình kết nối RabbitMQ của container vào Spring
  @DynamicPropertySource
  static void rabbit(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
  }

  // Tạo bảng inbox nếu chưa có, dọn bảng, queue và bộ đếm
  @BeforeEach
  void reset() {
    jdbc.execute(
        "create table if not exists processed_event (event_id uuid not null, consumer text not"
            + " null, processed_at timestamptz not null, primary key (event_id, consumer))");
    jdbc.execute("delete from processed_event");
    admin.purgeQueue("it.events", false);
    admin.purgeQueue("it.events.dlq", false);
    probe.reset();
  }

  // Số message sẵn sàng trong queue
  private long ready(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }

  // Dừng consumer rồi kiểm tra queue rỗng: message chưa ack sẽ quay lại ready khi channel đóng
  private void assertNothingUnacked() {
    registry.stop();
    try {
      assertThat(ready("it.events")).isZero();
    } finally {
      registry.start();
    }
  }

  // Gửi message vào it.src với body thô
  private void send(UUID eventId, byte[] body) {
    rabbit.send(
        "it.src",
        "any.key",
        MessageBuilder.withBody(body)
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Gửi envelope hợp lệ với eventId cho trước
  private void sendEnvelope(UUID eventId) throws Exception {
    var envelope =
        new EventEnvelope(
            eventId,
            "UserRegistered",
            1,
            Instant.now(),
            "it",
            "User",
            UUID.randomUUID(),
            Map.of("n", 1));
    send(eventId, mapper.writeValueAsBytes(envelope));
  }

  // Kịch bản: envelope hợp lệ được xử lý và ack
  // Input: 1 envelope hợp lệ vào it.src
  // Output: nghiệp vụ gọi 1 lần, it.events rỗng và sau khi dừng consumer vẫn rỗng (không unacked)
  @Test
  void messageIsConsumedAndAcked() throws Exception {
    sendEnvelope(UUID.randomUUID());

    await().atMost(Duration.ofSeconds(5)).until(() -> probe.calls() == 1);
    await().atMost(Duration.ofSeconds(5)).until(() -> ready("it.events") == 0);
    assertNothingUnacked();
    assertThat(probe.calls()).isEqualTo(1);
  }

  // Kịch bản: body hỏng vào DLQ ngay, không retry
  // Input: body "not json" vào it.src
  // Output: trong 2 s it.events.dlq có 1 message và nghiệp vụ gọi 0 lần
  @Test
  void malformedPayloadGoesToDlqWithoutRetry() throws Exception {
    send(UUID.randomUUID(), "not json".getBytes());

    await().atMost(Duration.ofSeconds(2)).until(() -> ready("it.events.dlq") == 1);
    assertThat(probe.calls()).isZero();
  }

  // Kịch bản: lỗi tạm thời retry 3 lần rồi vào DLQ
  // Input: handler luôn ném RuntimeException, 1 envelope hợp lệ
  // Output: tối đa 15 s it.events.dlq có 1 message, nghiệp vụ gọi 3 lần, it.events rỗng
  @Test
  void transientFailureRetriesThreeTimesThenDlq() throws Exception {
    probe.failWith(new RuntimeException("boom"));
    sendEnvelope(UUID.randomUUID());

    await().atMost(Duration.ofSeconds(15)).until(() -> ready("it.events.dlq") == 1);
    assertThat(probe.calls()).isEqualTo(3);
    assertThat(ready("it.events")).isZero();
  }

  // Kịch bản: cùng eventId gửi 2 lần chỉ xử lý 1 lần
  // Input: 2 message cùng eventId, handler gọi firstDelivery rồi đếm
  // Output: đếm nghiệp vụ = 1, cả hai message được ack (không unacked, DLQ rỗng)
  @Test
  void duplicateEventIdProcessedOnce() throws Exception {
    var business = new AtomicInteger();
    probe.behave(
        e -> {
          if (inbox.firstDelivery(e.eventId(), "it")) {
            business.incrementAndGet();
          }
        });
    var id = UUID.randomUUID();
    sendEnvelope(id);
    sendEnvelope(id);

    await().atMost(Duration.ofSeconds(5)).until(() -> probe.calls() == 2);
    await().atMost(Duration.ofSeconds(5)).until(() -> ready("it.events") == 0);
    assertNothingUnacked();
    assertThat(business.get()).isEqualTo(1);
    assertThat(ready("it.events.dlq")).isZero();
  }

  // Kịch bản: handler lỗi thì inbox rollback, lần retry sau mới ghi
  // Input: handler trong TransactionTemplate gọi firstDelivery, lần đầu ném lỗi, lần sau chạy xong
  // Output: processed_event đúng 1 dòng, nghiệp vụ hoàn tất 1 lần, DLQ rỗng
  @Test
  void failedHandlerRollsBackInbox() throws Exception {
    var done = new AtomicInteger();
    var attempts = new AtomicInteger();
    probe.behave(
        e ->
            tx.executeWithoutResult(
                s -> {
                  assertThat(inbox.firstDelivery(e.eventId(), "it")).isTrue();
                  if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("lần đầu lỗi");
                  }
                  done.incrementAndGet();
                }));
    sendEnvelope(UUID.randomUUID());

    await().atMost(Duration.ofSeconds(10)).until(() -> done.get() == 1);
    await().atMost(Duration.ofSeconds(5)).until(() -> ready("it.events") == 0);
    assertThat(jdbc.queryForObject("select count(*) from processed_event", Long.class))
        .isEqualTo(1);
    assertThat(ready("it.events.dlq")).isZero();
  }

  // Kịch bản: InboxGuard ghi vào bảng thuộc schema ngoài search_path
  // Input: schema it_inbox có processed_event, firstDelivery 2 lần cùng eventId
  // Output: lần 1 true, lần 2 false, 1 dòng ở it_inbox và 0 dòng ở public
  @Test
  void inboxWritesToConfiguredSchema() {
    jdbc.execute("create schema if not exists it_inbox");
    jdbc.execute(
        "create table if not exists it_inbox.processed_event (event_id uuid not null, consumer"
            + " text not null, processed_at timestamptz not null, primary key (event_id,"
            + " consumer))");
    jdbc.execute("delete from it_inbox.processed_event");
    var guard = new InboxGuard(jdbc, Clock.systemUTC(), "it_inbox");
    var id = UUID.randomUUID();

    assertThat(guard.firstDelivery(id, "it")).isTrue();
    assertThat(guard.firstDelivery(id, "it")).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from it_inbox.processed_event", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from public.processed_event", Long.class))
        .isZero();
  }
}

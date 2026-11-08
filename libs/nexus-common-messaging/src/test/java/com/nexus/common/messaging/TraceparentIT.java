package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra traceparent đi từ OutboxWriter qua cột headers tới header AMQP
@SpringBootTest(
    classes = ConsumerApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.application.name=it-trace",
      "nexus.messaging.relay.enabled=true",
      "nexus.messaging.relay.exchange=trace.events",
      "nexus.messaging.relay.interval=1h",
      "nexus.messaging.relay.confirm-timeout=2s",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true",
      "spring.rabbitmq.template.mandatory=true",
      "spring.rabbitmq.connection-timeout=2s",
      "spring.rabbitmq.channel-rpc-timeout=2s"
    })
class TraceparentIT extends PostgresIntegrationTest {

  private static final String QUEUE = "it.trace";
  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Autowired private OutboxRelay relay;
  @Autowired private OutboxEventDao dao;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;

  // Đẩy cấu hình kết nối RabbitMQ của container vào Spring
  @DynamicPropertySource
  static void rabbit(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
  }

  // Tạo lại bảng outbox_event bằng đúng SQL mẫu
  @BeforeAll
  static void createTable(@Autowired JdbcTemplate jdbc) throws Exception {
    jdbc.execute("drop table if exists outbox_event");
    var sql = new ClassPathResource("db/outbox/V0__outbox_template.sql");
    jdbc.execute(new String(sql.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  // Xóa bảng để IT khác dùng chung Postgres tự tạo lại
  @AfterAll
  static void dropTable(@Autowired JdbcTemplate jdbc) {
    jdbc.execute("drop table if exists outbox_event");
  }

  // Dọn bảng, tạo lại queue rỗng và bind # vào exchange
  @BeforeEach
  void reset() {
    jdbc.execute("delete from outbox_event");
    admin.declareExchange(new TopicExchange("trace.events", true, false));
    admin.deleteQueue(QUEUE);
    admin.declareQueue(new Queue(QUEUE));
    admin.declareBinding(
        BindingBuilder.bind(new Queue(QUEUE))
            .to(new TopicExchange("trace.events", true, false))
            .with("#"));
  }

  // Ghi một dòng outbox bằng writer với supplier cho trước, trong transaction riêng
  private void append(TraceparentSupplier supplier) {
    var writer = new OutboxWriter(dao, Clock.systemUTC(), supplier);
    tx.executeWithoutResult(
        s ->
            writer.append(
                new OutboxMessage(
                    "User", UUID.randomUUID(), "UserRegistered", 1, "user.registered", Map.of())));
  }

  // Kịch bản: traceparent đi từ writer tới header AMQP
  // Input: supplier cố định, append 1 dòng rồi relayOnce()
  // Output: cột headers có traceparent đúng giá trị và message nhận được mang header đó
  @Test
  void traceparentFlowsFromWriterToAmqpHeader() {
    append(() -> TRACEPARENT);

    assertThat(
            jdbc.queryForObject("select headers->>'traceparent' from outbox_event", String.class))
        .isEqualTo(TRACEPARENT);
    assertThat(relay.relayOnce()).isEqualTo(1);

    Message m = rabbit.receive(QUEUE, 5000);
    assertThat(m).isNotNull();
    assertThat(m.getMessageProperties().getHeaders()).containsEntry("traceparent", TRACEPARENT);
  }

  // Kịch bản: không có span hiện tại
  // Input: supplier trả null, append 1 dòng
  // Output: cột headers là {} như cũ
  @Test
  void noSpanMeansEmptyHeaders() {
    append(() -> null);

    assertThat(jdbc.queryForObject("select headers::text from outbox_event", String.class))
        .isEqualTo("{}");
  }
}

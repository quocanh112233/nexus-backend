package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra gauge nexus.outbox.pending bám theo số dòng outbox chưa gửi
@SpringBootTest(
    classes = ConsumerApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.application.name=it-metrics",
      "nexus.messaging.relay.enabled=true",
      "nexus.messaging.relay.exchange=metrics.events",
      "nexus.messaging.relay.interval=1h",
      "nexus.messaging.relay.confirm-timeout=2s",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true",
      "spring.rabbitmq.template.mandatory=true",
      "spring.rabbitmq.connection-timeout=2s",
      "spring.rabbitmq.channel-rpc-timeout=2s"
    })
@Import(OutboxMetricsIT.Registry.class)
class OutboxMetricsIT extends PostgresIntegrationTest {

  private static final String QUEUE = "it.metrics";

  @Autowired private OutboxRelay relay;
  @Autowired private OutboxWriter writer;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private RabbitAdmin admin;
  @Autowired private MeterRegistry meters;

  // Cấu hình test cấp MeterRegistry đơn giản
  @TestConfiguration
  static class Registry {

    // Registry trong bộ nhớ để đọc giá trị gauge
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

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
    admin.declareExchange(new TopicExchange("metrics.events", true, false));
    admin.deleteQueue(QUEUE);
    admin.declareQueue(new Queue(QUEUE));
    admin.declareBinding(
        BindingBuilder.bind(new Queue(QUEUE))
            .to(new TopicExchange("metrics.events", true, false))
            .with("#"));
  }

  // Bảo đảm broker không bị bỏ lại trạng thái pause nếu test lỗi giữa chừng
  @AfterEach
  void ensureUnpaused() {
    try {
      RabbitMqContainer.unpause();
    } catch (RuntimeException ignored) {
    }
  }

  // Ghi một dòng outbox trong transaction riêng
  private void append() {
    tx.executeWithoutResult(
        s ->
            writer.append(
                new OutboxMessage(
                    "User", UUID.randomUUID(), "UserRegistered", 1, "user.registered", Map.of())));
  }

  // Giá trị hiện tại của gauge nexus.outbox.pending
  private double pending() {
    return meters.get("nexus.outbox.pending").gauge().value();
  }

  // Kịch bản: gauge bám theo dòng chưa gửi
  // Input: append 2 dòng, relayOnce(), pause broker, append 1 dòng, relayOnce()
  // Output: gauge lần lượt 2, 0, rồi 1 vì broker pause nên dòng không gửi được
  @Test
  void pendingGaugeFollowsUnpublishedRows() {
    append();
    append();
    assertThat(pending()).isEqualTo(2);

    relay.relayOnce();
    assertThat(pending()).isZero();

    RabbitMqContainer.pause();
    append();
    relay.relayOnce();
    assertThat(pending()).isEqualTo(1);
  }
}

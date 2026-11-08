package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.consumerapp.probe.ProbeListener;
import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra dừng consumer giữa chừng vẫn chờ handler xong và ack, không làm mất message sau
@SpringBootTest(
    classes = ConsumerApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.application.name=it-shutdown",
      "it.probe.enabled=true",
      "spring.rabbitmq.connection-timeout=2s",
      "spring.rabbitmq.channel-rpc-timeout=2s"
    })
@DirtiesContext
class GracefulShutdownIT extends PostgresIntegrationTest {

  @Autowired private ProbeListener probe;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitListenerEndpointRegistry registry;
  @Autowired private JsonMapper mapper;

  // Đẩy cấu hình kết nối RabbitMQ của container vào Spring
  @DynamicPropertySource
  static void rabbit(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
  }

  // Số message sẵn sàng trong queue
  private long ready(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }

  // Gửi một envelope hợp lệ vào it.src
  private void sendEnvelope() throws Exception {
    var id = UUID.randomUUID();
    var envelope =
        new EventEnvelope(
            id, "UserRegistered", 1, Instant.now(), "it", "User", UUID.randomUUID(), Map.of());
    rabbit.send(
        "it.src",
        "any.key",
        MessageBuilder.withBody(mapper.writeValueAsBytes(envelope))
            .setMessageId(id.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Kịch bản: dừng consumer khi handler đang chạy dở
  // Input: handler ngủ 6 s (hơn timeout mặc định 5 s), gửi 2 message, stop registry giữa handler
  // Output: stop() chỉ trả về khi handler xong và đã ack; xử lý + còn trong queue = 2 message
  @Test
  void inFlightMessageFinishesAndIsAckedOnStop() throws Exception {
    admin.purgeQueue("it.events", false);
    probe.reset();
    var started = new CountDownLatch(1);
    var finished = new AtomicBoolean();
    probe.behave(
        e -> {
          started.countDown();
          try {
            Thread.sleep(6000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
          }
          finished.set(true);
        });
    sendEnvelope();
    sendEnvelope();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    registry.stop();
    try {
      assertThat(finished).isTrue();
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(probe.calls() + ready("it.events")).isEqualTo(2));
    } finally {
      probe.reset();
      registry.start();
    }
    await().atMost(Duration.ofSeconds(15)).until(() -> ready("it.events") == 0);
  }
}

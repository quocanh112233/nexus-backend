package com.nexus.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra relay outbox của notification chạy thật tới RabbitMQ khi bật cấu hình
@SpringBootTest(properties = "management.server.port=0")
@TestPropertySource(
    properties = {"nexus.messaging.relay.enabled=true", "nexus.messaging.relay.interval=100ms"})
class RelayWiringIT extends NotificationIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String EXCHANGE = "notification.events";

  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private JdbcTemplate jdbc;

  // Trỏ tới broker của container, vhost mặc định "/"
  @DynamicPropertySource
  static void relay(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Kịch bản: dòng outbox MentionCreated được relay lên notification.events
  // Input: queue tạm bind # vào notification.events, chèn một dòng outbox MentionCreated
  // Output: trong 5s nhận mention.created có eventType MentionCreated, payload.userId khớp
  @Test
  void outboxMentionCreatedIsRelayed() throws Exception {
    String queue = "it.relay.notification." + UUID.randomUUID();
    admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
    admin.declareQueue(new Queue(queue));
    admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, EXCHANGE, "#", null));
    UUID user = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    jdbc.update(
        "insert into notification.outbox_event (id, aggregate_type, aggregate_id, event_type,"
            + " event_version, routing_key, payload, created_at)"
            + " values (?, 'Mention', ?, 'MentionCreated', 1, 'mention.created',"
            + " cast(? as jsonb), now())",
        UUID.randomUUID(),
        messageId,
        "{\"userId\":\""
            + user
            + "\",\"channelId\":\""
            + UUID.randomUUID()
            + "\","
            + "\"serverId\":null,\"messageId\":\""
            + messageId
            + "\","
            + "\"authorId\":\""
            + UUID.randomUUID()
            + "\",\"mentionCount\":1}");

    JsonNode envelope = awaitEvent(queue, "mention.created", user.toString());

    assertThat(envelope).isNotNull();
    assertThat(envelope.get("eventType").asText()).isEqualTo("MentionCreated");
  }

  // Đọc queue tới khi gặp envelope khớp routing key và userId, bỏ qua message tồn đọng
  private JsonNode awaitEvent(String queue, String routingKey, String userId) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      Message m = rabbit.receive(queue, 500);
      if (m != null && routingKey.equals(m.getMessageProperties().getReceivedRoutingKey())) {
        JsonNode envelope = MAPPER.readTree(m.getBody());
        if (userId.equals(envelope.get("payload").get("userId").asText())) {
          return envelope;
        }
      }
    }
    return null;
  }
}

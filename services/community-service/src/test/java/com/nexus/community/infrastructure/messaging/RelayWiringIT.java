package com.nexus.community.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra relay outbox của community chạy thật tới RabbitMQ khi bật cấu hình
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"nexus.messaging.relay.enabled=true", "nexus.messaging.relay.interval=100ms"})
class RelayWiringIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String EXCHANGE = "community.events";

  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitTemplate rabbit;
  @LocalServerPort private int port;

  // Trỏ tới broker của container, vhost mặc định "/"
  @DynamicPropertySource
  static void relay(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Kịch bản: tạo server thì relay phát server.created lên community.events
  // Input: queue tạm bind # vào community.events, POST /api/v1/servers
  // Output: trong 5s có message server.created, producer community-service, payload.serverId khớp
  @Test
  void createServerPublishesServerCreated() throws Exception {
    String queue = "it.relay.community." + UUID.randomUUID();
    admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
    admin.declareQueue(new Queue(queue));
    admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, EXCHANGE, "#", null));

    String serverId = createServer();

    JsonNode created = awaitEvent(queue, "server.created", "serverId", serverId);
    assertThat(created).isNotNull();
    assertThat(created.get("producer").asText()).isEqualTo("community-service");
  }

  // Tạo server qua HTTP bằng một user ngẫu nhiên và trả id server
  private String createServer() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers"))
                    .header("Authorization", bearer(UUID.randomUUID()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Relay\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Đọc queue tới khi gặp envelope khớp routing key và payload, bỏ qua message tồn đọng của IT khác
  private JsonNode awaitEvent(String queue, String routingKey, String field, String value)
      throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      Message m = rabbit.receive(queue, 500);
      if (m != null && routingKey.equals(m.getMessageProperties().getReceivedRoutingKey())) {
        JsonNode envelope = MAPPER.readTree(m.getBody());
        if (value.equals(envelope.get("payload").get(field).asText())) {
          return envelope;
        }
      }
    }
    return null;
  }
}

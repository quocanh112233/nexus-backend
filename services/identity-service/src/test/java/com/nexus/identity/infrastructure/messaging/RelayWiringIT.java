package com.nexus.identity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
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

// Kiểm tra relay outbox của identity chạy thật tới RabbitMQ khi bật cấu hình
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"nexus.messaging.relay.enabled=true", "nexus.messaging.relay.interval=100ms"})
class RelayWiringIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String EXCHANGE = "identity.events";

  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitTemplate rabbit;
  @LocalServerPort private int port;

  // Trỏ tới broker của container, vhost mặc định "/"
  @DynamicPropertySource
  static void relay(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Kịch bản: đăng ký user thì relay phát user.registered lên identity.events
  // Input: queue tạm bind # vào identity.events, POST /api/v1/auth/register
  // Output: trong 5s nhận message user.registered, producer identity-service, payload.userId khớp
  @Test
  void registerUserPublishesUserRegistered() throws Exception {
    String queue = "it.relay.identity." + UUID.randomUUID();
    admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
    admin.declareQueue(new Queue(queue));
    admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, EXCHANGE, "#", null));
    String tag = UUID.randomUUID().toString().substring(0, 8);

    String userId = register(tag);

    JsonNode envelope = awaitEvent(queue, "user.registered", "userId", userId);
    assertThat(envelope).isNotNull();
    assertThat(envelope.get("producer").asText()).isEqualTo("identity-service");
  }

  // Gọi register qua HTTP và trả id user vừa tạo
  private String register(String tag) throws Exception {
    String body =
        MAPPER.writeValueAsString(
            java.util.Map.of("email", tag + "@x.io", "username", tag, "password", "secret-pass-1"));
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
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

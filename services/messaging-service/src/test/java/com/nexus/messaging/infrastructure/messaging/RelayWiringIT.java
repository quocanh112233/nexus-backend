package com.nexus.messaging.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.testsupport.RabbitMqContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra relay outbox của messaging chạy thật tới RabbitMQ khi bật cấu hình
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@TestPropertySource(
    properties = {"nexus.messaging.relay.enabled=true", "nexus.messaging.relay.interval=100ms"})
class RelayWiringIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String EXCHANGE = "messaging.events";

  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitTemplate rabbit;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;

  // Trỏ tới broker của container, vhost mặc định "/"
  @DynamicPropertySource
  static void relay(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Kịch bản: gửi tin thì relay phát message.created lên messaging.events
  // Input: queue tạm bind # vào messaging.events, POST tin có nonce vào một kênh
  // Output: trong 5s nhận message.created, producer messaging-service, payload.nonce khớp
  @Test
  void sendMessagePublishesMessageCreated() throws Exception {
    String queue = "it.relay.messaging." + UUID.randomUUID();
    admin.declareExchange(new TopicExchange(EXCHANGE, true, false));
    admin.declareQueue(new Queue(queue));
    admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, EXCHANGE, "#", null));
    when(checker.check(any(), any()))
        .thenReturn(
            new ChannelAccess(
                UUID.randomUUID(),
                EnumSet.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES)));
    String nonce = "relay-" + UUID.randomUUID();

    HttpResponse<String> response = send(nonce);

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode envelope = awaitEvent(queue, "message.created", "nonce", nonce);
    assertThat(envelope).isNotNull();
    assertThat(envelope.get("producer").asText()).isEqualTo("messaging-service");
  }

  // POST tin có nonce vào một kênh ngẫu nhiên bằng một user ngẫu nhiên
  private HttpResponse<String> send(String nonce) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/channels/"
                            + UUID.randomUUID()
                            + "/messages"))
                .header("Authorization", bearer(UUID.randomUUID()))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"content\":\"hi\",\"nonce\":\"" + nonce + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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

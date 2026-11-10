package com.nexus.presence.interfaces.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.domain.relation.RelationshipProjection;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.RedisContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra listener messaging.events với RabbitMQ và Redis thật, gRPC trỏ vào cổng chết
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.grpc.service-token=test-token",
      "app.grpc.community.target=localhost:1",
      "app.grpc.messaging.target=localhost:1"
    })
class FriendEventListenerIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String QUEUE = "presence.messaging.events";

  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private RelationshipProjection projection;

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();

  // Trỏ broker (vhost "/") và Redis tới container
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    RedisContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Dọn queue chính và DLQ, nạp sẵn hai user rỗng
  @BeforeEach
  void setUp() {
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
    projection.load(a, Set.of(), Set.of(), projection.revision(a));
    projection.load(b, Set.of(), Set.of(), projection.revision(b));
  }

  // Kịch bản: hai user loaded chấp nhận kết bạn rồi hủy kết bạn
  // Input: FriendRequestAccepted (requesterId a, addresseeId b) rồi FriendRemoved (b, a)
  // Output: sau Accepted mỗi bên có bên kia, sau Removed cả hai tập rỗng
  @Test
  void acceptedThenRemovedUpdatesBothSides() {
    publish(
        "friend-request.accepted",
        "FriendRequestAccepted",
        Map.of("requestId", UUID.randomUUID(), "requesterId", a, "addresseeId", b));

    await().atMost(Duration.ofSeconds(10)).until(() -> projection.isFriend(a, b));
    assertThat(projection.friendsOf(b)).containsExactly(a);

    publish("friend.removed", "FriendRemoved", Map.of("userId", b, "friendId", a));

    await().atMost(Duration.ofSeconds(10)).until(() -> projection.friendsOf(a).isEmpty());
    assertThat(projection.friendsOf(b)).isEmpty();
  }

  // Kịch bản: eventType lạ, payload rỗng, theo sau là một event hợp lệ làm mốc
  // Input: Whatever trên friend-request.accepted rồi FriendRequestAccepted (a, b)
  // Output: event mốc được áp, queue và DLQ rỗng
  @Test
  void unknownEventAckedAndIgnored() {
    rabbit.invoke(
        operations -> {
          publish("friend-request.accepted", "Whatever", Map.of());
          publish(
              "friend-request.accepted",
              "FriendRequestAccepted",
              Map.of("requestId", UUID.randomUUID(), "requesterId", a, "addresseeId", b));
          return null;
        });

    await().atMost(Duration.ofSeconds(10)).until(() -> projection.isFriend(a, b));
    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE) == 0);
    assertThat(count(QUEUE + ".dlq")).isZero();
  }

  // Kịch bản: payload FriendRequestAccepted thiếu addresseeId và FriendRemoved sai UUID
  // Input: Accepted chỉ có requesterId, Removed với friendId "x"
  // Output: trong 2 giây DLQ có 2 message, queue chính rỗng, a không có bạn
  @Test
  void malformedPayloadGoesToDlqWithoutRetry() {
    publish("friend-request.accepted", "FriendRequestAccepted", Map.of("requesterId", a));
    publish("friend.removed", "FriendRemoved", Map.of("userId", a, "friendId", "x"));

    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE + ".dlq") == 2);
    assertThat(count(QUEUE)).isZero();
    assertThat(projection.friendsOf(a)).isEmpty();
  }

  // Gửi envelope thẳng vào exchange messaging.events với routing key cho trước
  private void publish(String routingKey, String type, Map<String, Object> payload) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            eventId,
            "eventType",
            type,
            "eventVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "producer",
            "messaging-service",
            "aggregateType",
            "Friendship",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        "messaging.events",
        routingKey,
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Số message sẵn sàng trong queue
  private long count(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }
}

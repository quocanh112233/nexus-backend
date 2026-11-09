package com.nexus.presence.interfaces.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.RelationUnavailableException;
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

// Kiểm tra listener community.events với RabbitMQ và Redis thật, gRPC Community trỏ vào cổng chết
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.grpc.service-token=test-token",
      "app.grpc.community.target=localhost:1"
    })
class CommunityEventListenerIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String QUEUE = "presence.community.events";

  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private RelationshipProjection projection;
  @Autowired private EnsureLoadedUseCase ensureLoaded;

  // Trỏ broker (vhost "/") và Redis tới container
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    RedisContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Dọn queue chính và DLQ để mỗi test bắt đầu từ trạng thái sạch
  @BeforeEach
  void purge() {
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
  }

  // Kịch bản: user đã loaded bị ban khỏi một trong hai server
  // Input: load user {s1, s2}, publish MemberBanned của s1
  // Output: serversOf(user) chỉ còn s2 và membersOf(s1) không còn user
  @Test
  void memberBannedRemovesServerOfLoadedUser() {
    UUID user = UUID.randomUUID();
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    projection.load(user, Set.of(s1, s2), projection.revision(user));

    publish("member.banned", "MemberBanned", Map.of("serverId", s1, "userId", user));

    await().atMost(Duration.ofSeconds(10)).until(() -> projection.serversOf(user).size() == 1);
    assertThat(projection.serversOf(user)).containsExactly(s2);
    assertThat(projection.membersOf(s1)).doesNotContain(user);
  }

  // Kịch bản: eventType lạ, payload rỗng, theo sau là một event hợp lệ làm mốc
  // Input: eventType Whatever với routing key member.left rồi MemberLeft của user đã loaded
  // Output: event mốc được xử lý (user mất server), queue và DLQ rỗng
  @Test
  void unknownEventAckedAndIgnored() {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    projection.load(user, Set.of(server), projection.revision(user));

    rabbit.invoke(
        operations -> {
          publish("member.left", "Whatever", Map.of());
          publish("member.left", "MemberLeft", Map.of("serverId", server, "userId", user));
          return null;
        });

    await().atMost(Duration.ofSeconds(10)).until(() -> projection.serversOf(user).isEmpty());
    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE) == 0);
    assertThat(count(QUEUE + ".dlq")).isZero();
  }

  // Kịch bản: payload MemberJoined thiếu userId
  // Input: MemberJoined chỉ có serverId
  // Output: trong 2 giây DLQ có 1 message và queue chính rỗng (không retry)
  @Test
  void malformedPayloadGoesToDlqWithoutRetry() {
    publish("member.joined", "MemberJoined", Map.of("serverId", UUID.randomUUID()));

    await().atMost(Duration.ofSeconds(2)).until(() -> count(QUEUE + ".dlq") == 1);
    assertThat(count(QUEUE)).isZero();
  }

  // Kịch bản: Community không với tới được khi nạp bù
  // Input: ensure(user) chưa loaded, loader gRPC trỏ vào cổng chết
  // Output: ném RelationUnavailableException và user vẫn chưa loaded
  @Test
  void loaderUnavailableOnEnsureThrowsRelationUnavailable() {
    UUID user = UUID.randomUUID();

    assertThatThrownBy(() -> ensureLoaded.ensure(user))
        .isInstanceOf(RelationUnavailableException.class);
    assertThat(projection.isLoaded(user)).isFalse();
  }

  // Gửi envelope thẳng vào exchange community.events với routing key cho trước
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
            "community-service",
            "aggregateType",
            "Test",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        "community.events",
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

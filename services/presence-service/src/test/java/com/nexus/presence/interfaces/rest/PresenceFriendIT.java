package com.nexus.presence.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.PresenceChangeTap;
import com.nexus.presence.application.presence.SessionService;
import com.nexus.presence.application.relation.FriendListLoader;
import com.nexus.presence.application.relation.ServerListLoader;
import com.nexus.presence.domain.relation.RelationshipProjection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// IT đầu-cuối FR-080/FR-083: bạn bè thấy trạng thái nhau, Redis và Rabbit thật, loader gRPC giả
class PresenceFriendIT extends PresenceApiIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Map<UUID, Set<UUID>> SERVERS = new ConcurrentHashMap<>();
  private static final Map<UUID, Set<UUID>> FRIENDS = new ConcurrentHashMap<>();

  @Autowired private RelationshipProjection projection;
  @Autowired private SessionService sessions;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;

  private PresenceChangeTap tap;
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID x = UUID.randomUUID();

  // Thay loader gRPC Community và Messaging bằng bản đồ trong bộ nhớ
  @TestConfiguration
  static class FakeLoaders {

    // Loader server đọc SERVERS, user lạ thì rỗng
    @Bean
    @Primary
    ServerListLoader fakeServerListLoader() {
      return id -> SERVERS.getOrDefault(id, Set.of());
    }

    // Loader bạn đọc FRIENDS, user lạ thì rỗng
    @Bean
    @Primary
    FriendListLoader fakeFriendListLoader() {
      return id -> FRIENDS.getOrDefault(id, Set.of());
    }
  }

  // Dựng queue tạm; a, b là bạn trong Messaging, mỗi người một server riêng, x là người lạ
  @BeforeEach
  void setUp() {
    tap = new PresenceChangeTap(admin, rabbit);
    SERVERS.put(a, Set.of(UUID.randomUUID()));
    SERVERS.put(b, Set.of(UUID.randomUUID()));
    SERVERS.put(x, Set.of(UUID.randomUUID()));
    FRIENDS.put(a, Set.of(b));
    FRIENDS.put(b, Set.of(a));
  }

  // Xóa queue tạm
  @AfterEach
  void tearDown() {
    tap.close();
  }

  // Kịch bản: a, b kết bạn trước khi được nạp, không chung server; a online rồi b online
  // Input: connect a, connect b, GET userIds=b bởi a và bởi người lạ x
  // Output: PresenceChanged ONLINE của b có friendIds ∋ a, không ∋ x; a thấy b ONLINE, x thấy []
  @Test
  void friendWithoutSharedServerSeesOnline() throws Exception {
    sessions.connect("s-" + a, a, "gw-1", Instant.now());
    sessions.connect("s-" + b, b, "gw-1", Instant.now());

    JsonNode friendIds = tap.awaitStatus(b, "ONLINE").get("payload").get("friendIds");
    assertThat(friendIds)
        .extracting(JsonNode::asText)
        .containsExactly(a.toString())
        .doesNotContain(x.toString());
    JsonNode body = MAPPER.readTree(getPresence("?userIds=" + b, bearer(a)).body());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("status").asText()).isEqualTo("ONLINE");
    assertThat(getPresence("?userIds=" + b, bearer(x)).body()).isEqualTo("[]");
  }

  // Kịch bản: a, b là bạn không chung server rồi hủy kết bạn, sau đó b offline
  // Input: connect a, b; FriendRemoved (b, a) qua messaging.events; disconnect b
  // Output: GET của a bỏ hẳn b (không OFFLINE giả); OFFLINE của b có friendIds không ∋ a
  @Test
  void friendRemovedHidesStatusWithoutFakeOffline() throws Exception {
    sessions.connect("s-" + a, a, "gw-1", Instant.now());
    sessions.connect("s-" + b, b, "gw-1", Instant.now());
    assertThat(MAPPER.readTree(getPresence("?userIds=" + b, bearer(a)).body())).hasSize(1);

    FRIENDS.put(a, Set.of());
    FRIENDS.put(b, Set.of());
    publishFriendRemoved(b, a);

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "[]".equals(getPresence("?userIds=" + b, bearer(a)).body()));
    sessions.disconnect("s-" + b);
    JsonNode friendIds = tap.awaitStatus(b, "OFFLINE").get("payload").get("friendIds");
    assertThat(friendIds).extracting(JsonNode::asText).doesNotContain(a.toString());
  }

  // Kịch bản: a, b là bạn và chung server s rồi hủy kết bạn
  // Input: cả hai có {s}, connect b, FriendRemoved (a, b), GET userIds=b bởi a
  // Output: a vẫn thấy b ONLINE qua đường chung server
  @Test
  void exFriendSharingServerStaysVisible() throws Exception {
    UUID s = UUID.randomUUID();
    SERVERS.put(a, Set.of(s));
    SERVERS.put(b, Set.of(s));
    sessions.connect("s-" + b, b, "gw-1", Instant.now());
    assertThat(MAPPER.readTree(getPresence("?userIds=" + b, bearer(a)).body())).hasSize(1);

    publishFriendRemoved(a, b);

    await().atMost(Duration.ofSeconds(10)).until(() -> !projection.isFriend(a, b));
    JsonNode body = MAPPER.readTree(getPresence("?userIds=" + b, bearer(a)).body());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("status").asText()).isEqualTo("ONLINE");
  }

  // Gửi FriendRemoved thẳng vào exchange messaging.events
  private void publishFriendRemoved(UUID user, UUID friend) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            eventId,
            "eventType",
            "FriendRemoved",
            "eventVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "producer",
            "messaging-service",
            "aggregateType",
            "Friendship",
            "aggregateId",
            user,
            "payload",
            Map.of("userId", user, "friendId", friend));
    rabbit.send(
        "messaging.events",
        "friend.removed",
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }
}

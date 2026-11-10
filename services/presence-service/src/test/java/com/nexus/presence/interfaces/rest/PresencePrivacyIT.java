package com.nexus.presence.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.PresenceChangeTap;
import com.nexus.presence.application.presence.SessionService;
import com.nexus.presence.domain.relation.RelationshipProjection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// IT riêng tư NFR-018: chỉ người chung server thấy trạng thái, Rabbit và Redis thật
class PresencePrivacyIT extends PresenceApiIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private RelationshipProjection projection;
  @Autowired private SessionService sessions;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;

  private PresenceChangeTap tap;
  private final UUID a = UUID.randomUUID();
  private final UUID x = UUID.randomUUID();

  // Dựng queue tạm nhận PresenceChanged
  @BeforeEach
  void setUp() {
    tap = new PresenceChangeTap(admin, rabbit);
  }

  // Xóa queue tạm
  @AfterEach
  void tearDown() {
    tap.close();
  }

  // Kịch bản: A đang ONLINE, X không chung server nào với A
  // Input: GET userIds=A bởi X
  // Output: 200 với mảng rỗng, không có phần tử OFFLINE giả
  @Test
  void strangerDoesNotSeeUserInGetPresence() throws Exception {
    projection.load(a, Set.of(UUID.randomUUID()), Set.of(), projection.revision(a));
    projection.load(x, Set.of(UUID.randomUUID()), Set.of(), projection.revision(x));
    sessions.connect("s-" + a, a, "gw-1", Instant.now());

    var response = getPresence("?userIds=" + a, bearer(x));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("[]");
  }

  // Kịch bản: A đang ONLINE, B chung server với A
  // Input: GET userIds=A bởi B
  // Output: đúng 1 phần tử {A, ONLINE}
  @Test
  void userSharingServerSeesOnline() throws Exception {
    UUID b = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    projection.load(a, Set.of(server), Set.of(), projection.revision(a));
    projection.load(b, Set.of(server), Set.of(), projection.revision(b));
    sessions.connect("s-" + a, a, "gw-1", Instant.now());

    JsonNode body = MAPPER.readTree(getPresence("?userIds=" + a, bearer(b)).body());

    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("userId").asText()).isEqualTo(a.toString());
    assertThat(body.get(0).get("status").asText()).isEqualTo("ONLINE");
  }

  // Kịch bản: B chỉ chung đúng server S với A rồi bị ban khỏi S
  // Input: B đã nạp, publish MemberBanned (S, B), rồi GET userIds=A bởi B
  // Output: trước ban B thấy A, sau ban GET trả mảng rỗng
  @Test
  void bannedFromOnlySharedServerNoLongerSeen() throws Exception {
    UUID b = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    projection.load(a, Set.of(server), Set.of(), projection.revision(a));
    projection.load(b, Set.of(server), Set.of(), projection.revision(b));
    sessions.connect("s-" + a, a, "gw-1", Instant.now());
    assertThat(MAPPER.readTree(getPresence("?userIds=" + a, bearer(b)).body())).hasSize(1);

    publishBanned(server, b);

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "[]".equals(getPresence("?userIds=" + a, bearer(b)).body()));
  }

  // Kịch bản: A có server s1, s2 và người lạ X có server s3, A mở session đầu tiên
  // Input: SessionService.connect của A, đọc PresenceChanged trên queue tạm
  // Output: payload serverIds đúng {s1, s2}, không chứa s3
  @Test
  void presenceChangedPayloadOnlyListsSharedServerIds() {
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    UUID s3 = UUID.randomUUID();
    projection.load(a, Set.of(s1, s2), Set.of(), projection.revision(a));
    projection.load(x, Set.of(s3), Set.of(), projection.revision(x));

    sessions.connect("s-" + a, a, "gw-1", Instant.now());

    JsonNode serverIds = tap.awaitStatus(a, "ONLINE").get("payload").get("serverIds");
    assertThat(serverIds)
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder(s1.toString(), s2.toString())
        .doesNotContain(s3.toString());
  }

  // Kịch bản: Community không với tới được và viewer chưa từng được nạp quan hệ
  // Input: A ONLINE, X chưa loaded, GET userIds=A,X bởi X
  // Output: 200 với mảng rỗng, kể cả chính X (fail-closed, không 5xx)
  @Test
  void loaderDownMeansNobodyVisible() throws Exception {
    projection.load(a, Set.of(UUID.randomUUID()), Set.of(), projection.revision(a));
    sessions.connect("s-" + a, a, "gw-1", Instant.now());

    var response = getPresence("?userIds=" + a + "," + x, bearer(x));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("[]");
  }

  // Gửi MemberBanned thẳng vào exchange community.events
  private void publishBanned(UUID server, UUID user) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            eventId,
            "eventType",
            "MemberBanned",
            "eventVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "producer",
            "community-service",
            "aggregateType",
            "Member",
            "aggregateId",
            user,
            "payload",
            Map.of("serverId", server, "userId", user));
    rabbit.send(
        "community.events",
        "member.banned",
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }
}

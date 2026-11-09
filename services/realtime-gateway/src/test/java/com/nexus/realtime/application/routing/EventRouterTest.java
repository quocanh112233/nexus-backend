package com.nexus.realtime.application.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.messaging.EventEnvelope;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// Kiểm tra bảng định tuyến event sang destination STOMP
class EventRouterTest {

  private static final UUID SERVER = UUID.randomUUID();
  private static final UUID CHANNEL = UUID.randomUUID();
  private static final UUID USER = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final Map<String, Object> PRESENCE_DATA =
      Map.of("userId", USER.toString(), "status", "ONLINE", "changedAt", "2026-01-01T00:00:00Z");

  // Tạo envelope với payload cho sẵn
  private static EventEnvelope envelope(String type, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(), type, 1, Instant.now(), "test", "Agg", UUID.randomUUID(), payload);
  }

  // Một dòng bảng: event, payload, các route mong đợi
  private static Arguments row(String type, Map<String, Object> payload, Route... routes) {
    return Arguments.of(type, payload, List.of(routes));
  }

  // Route tới topic kênh
  private static Route channel() {
    return new Route("/topic/channels/" + CHANNEL, null, null);
  }

  // Route tới topic server với data thay thế (null là giữ nguyên payload)
  private static Route server(UUID id, Map<String, Object> data) {
    return new Route("/topic/servers/" + id, null, data);
  }

  // Route tới topic server giữ nguyên payload
  private static Route server() {
    return server(SERVER, null);
  }

  // Route tới queue riêng của user với data thay thế (null là giữ nguyên payload)
  private static Route user(UUID id, Map<String, Object> data) {
    return new Route("/queue/events", id, data);
  }

  // Route tới queue riêng của user giữ nguyên payload
  private static Route user(UUID id) {
    return user(id, null);
  }

  // Payload PresenceChanged đầy đủ với danh sách người nhận cho trước
  private static Map<String, Object> presence(List<String> serverIds, List<String> friendIds) {
    return of(
        "userId",
        USER.toString(),
        "status",
        "ONLINE",
        "changedAt",
        "2026-01-01T00:00:00Z",
        "serverIds",
        serverIds,
        "friendIds",
        friendIds);
  }

  // Dựng payload từ cặp khóa, giá trị
  private static Map<String, Object> of(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // Bảng event, payload và route mong đợi
  private static Stream<Arguments> table() {
    Map<String, Object> ch = of("channelId", CHANNEL.toString());
    Map<String, Object> sv = of("serverId", SERVER.toString());
    Map<String, Object> svUser = of("serverId", SERVER.toString(), "userId", USER.toString());
    Stream.Builder<Arguments> rows = Stream.builder();
    for (String t :
        List.of(
            "MessageCreated",
            "MessageEdited",
            "MessageDeleted",
            "ReactionAdded",
            "ReactionRemoved",
            "TypingStarted")) {
      rows.add(row(t, ch, channel()));
    }
    for (String t :
        List.of(
            "ServerUpdated",
            "ServerDeleted",
            "CategoryCreated",
            "CategoryUpdated",
            "CategoryDeleted",
            "ChannelCreated",
            "ChannelUpdated",
            "ChannelDeleted",
            "ChannelPermissionsChanged",
            "RoleCreated",
            "RoleUpdated",
            "RoleDeleted",
            "MemberLeft",
            "MemberUnbanned",
            "MemberRolesChanged")) {
      rows.add(row(t, svUser, server()));
    }
    rows.add(row("MemberJoined", svUser, server(), user(USER)));
    Map<String, Object> kicked =
        of("serverId", SERVER.toString(), "userId", USER.toString(), "kickedBy", OTHER.toString());
    rows.add(row("MemberKicked", kicked, server(SERVER, svUser), user(USER)));
    Map<String, Object> banned =
        of(
            "serverId", SERVER.toString(),
            "userId", USER.toString(),
            "bannedBy", OTHER.toString(),
            "reason", "spam",
            "wasMember", true);
    Map<String, Object> bannedPublic =
        of("serverId", SERVER.toString(), "userId", USER.toString(), "wasMember", true);
    rows.add(row("MemberBanned", banned, server(SERVER, bannedPublic), user(USER)));
    rows.add(row("ServerCreated", of("ownerId", OTHER.toString()), user(OTHER)));
    Map<String, Object> presence =
        presence(List.of(SERVER.toString(), OTHER.toString()), List.of());
    rows.add(
        row(
            "PresenceChanged",
            presence,
            server(SERVER, PRESENCE_DATA),
            server(OTHER, PRESENCE_DATA)));
    rows.add(
        row(
            "PresenceChanged",
            presence(List.of(), List.of(OTHER.toString())),
            user(OTHER, PRESENCE_DATA)));
    rows.add(row("SomethingNew", sv));
    return rows.build();
  }

  // Kịch bản: mỗi event đang tồn tại ánh xạ tới đúng destination
  // Input: envelope của từng loại event với payload tương ứng
  // Output: danh sách route đúng bảng realtime-design, event lạ thì rỗng
  @ParameterizedTest
  @MethodSource("table")
  void routesFollowTable(String type, Map<String, Object> payload, List<Route> expected) {
    assertThat(EventRouter.route(envelope(type, payload))).containsExactlyElementsOf(expected);
  }

  // Kịch bản: payload thiếu id hoặc id sai định dạng
  // Input: MessageCreated không có channelId, ServerCreated với ownerId không phải UUID
  // Output: ném IllegalArgumentException
  @Test
  void missingOrBadIdThrows() {
    assertThatThrownBy(() -> EventRouter.route(envelope("MessageCreated", Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> EventRouter.route(envelope("ServerCreated", Map.of("ownerId", "not-a-uuid"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Kịch bản: Frame dựng từ envelope
  // Input: envelope MessageCreated có eventId, occurredAt và payload
  // Output: frame mang đúng type, eventId, occurredAt ISO và data là payload
  @Test
  void frameCarriesEnvelopeFields() {
    EventEnvelope e = envelope("MessageCreated", Map.of("messageId", "m1"));
    Frame frame = Frame.of(e, null);
    assertThat(frame)
        .isEqualTo(
            new Frame(
                "MessageCreated",
                e.eventId().toString(),
                e.occurredAt().toString(),
                Map.of("messageId", "m1")));
  }

  // Kịch bản: PresenceChanged chỉ dùng danh sách người nhận để định tuyến
  // Input: payload có userId, status, changedAt, serverIds (2) và friendIds (1)
  // Output: mọi route mang data đúng ba khóa userId, status, changedAt
  @Test
  void presenceDataHasOnlyThreeKeys() {
    List<Route> routes =
        EventRouter.route(
            envelope(
                "PresenceChanged",
                presence(List.of(SERVER.toString(), OTHER.toString()), List.of(USER.toString()))));
    assertThat(routes).hasSize(3);
    assertThat(routes)
        .allSatisfy(
            r ->
                assertThat(r.data().keySet())
                    .containsExactlyInAnyOrder("userId", "status", "changedAt"));
  }

  // Kịch bản: MemberKicked giao tới hai nơi với dữ liệu khác nhau
  // Input: payload kick có serverId, userId, kickedBy
  // Output: topic server chỉ có serverId, userId; queue của người bị kick giữ payload đầy đủ
  @Test
  void kickedTopicDropsKickedByButUserQueueKeepsIt() {
    Map<String, Object> payload =
        of("serverId", SERVER.toString(), "userId", USER.toString(), "kickedBy", OTHER.toString());
    List<Route> routes = EventRouter.route(envelope("MemberKicked", payload));
    assertThat(routes.get(0).data().keySet()).containsExactlyInAnyOrder("serverId", "userId");
    assertThat(routes.get(1).data()).isNull();
  }

  // Kịch bản: MemberBanned giao tới hai nơi với dữ liệu khác nhau
  // Input: payload ban có bannedBy, reason, wasMember
  // Output: topic server không có bannedBy/reason, queue của người bị ban giữ payload đầy đủ
  @Test
  void bannedTopicDropsReasonButUserQueueKeepsIt() {
    Map<String, Object> payload =
        of(
            "serverId", SERVER.toString(),
            "userId", USER.toString(),
            "bannedBy", OTHER.toString(),
            "reason", "spam",
            "wasMember", true);
    List<Route> routes = EventRouter.route(envelope("MemberBanned", payload));
    assertThat(routes.get(0).data().keySet())
        .containsExactlyInAnyOrder("serverId", "userId", "wasMember");
    assertThat(routes.get(1).data()).isNull();
  }
}

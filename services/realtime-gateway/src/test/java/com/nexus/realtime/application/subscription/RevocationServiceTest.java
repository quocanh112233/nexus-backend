package com.nexus.realtime.application.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.common.messaging.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra RevocationService với AccessChecker giả và sổ subscription thật
class RevocationServiceTest {

  private final UUID server = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final Set<UUID> denied = new HashSet<>();
  private boolean failing;
  private int checks;
  private Runnable onCheck = () -> {};
  private final SubscriptionRegistry registry = new SubscriptionRegistry();
  private final List<String> revoked = new ArrayList<>();
  private final List<String> reasons = new ArrayList<>();
  private final RevocationService service =
      new RevocationService(
          registry,
          new AccessChecker() {
            // Từ chối user trong denied, ném lỗi hạ tầng khi failing
            @Override
            public Optional<UUID> canViewChannel(UUID userId, UUID channelId) {
              return check(userId) ? Optional.of(server) : Optional.empty();
            }

            // Từ chối user trong denied, ném lỗi hạ tầng khi failing
            @Override
            public boolean isServerMember(UUID userId, UUID serverId) {
              return check(userId);
            }

            // Trả quyền của user theo cấu hình giả
            private boolean check(UUID userId) {
              checks++;
              onCheck.run();
              if (failing) {
                throw new AccessCheckUnavailableException("down", null);
              }
              return !denied.contains(userId);
            }
          },
          (s, reason) -> {
            revoked.add(s.sessionId() + " " + s.destination());
            reasons.add(reason);
          });

  // Tạo envelope event với payload cho sẵn
  private static EventEnvelope event(String type, Map<String, Object> payload) {
    return new EventEnvelope(
        UUID.randomUUID(), type, 1, Instant.now(), "test", "Agg", UUID.randomUUID(), payload);
  }

  // Đăng ký subscription của user trên session riêng
  private void sub(String session, UUID userId, String destination) {
    sub(session, userId, destination, server);
  }

  // Đăng ký subscription gắn với server cho trước
  private void sub(String session, UUID userId, String destination, UUID serverId) {
    registry.add(new SessionSubscription(session, userId, destination, destination, serverId));
  }

  // Kịch bản: user bị kick
  // Input: user sub topic server, topic kênh và user queue, MemberKicked của user
  // Output: server và kênh bị thu hồi không hỏi checker, lý do là MemberKicked, user queue còn
  @Test
  void kickedUserLosesServerAndItsChannelSubscriptions() {
    sub("s1", user, "/topic/servers/" + server);
    sub("s1", user, "/topic/channels/" + channel);
    sub("s1", user, "/user/queue/events", null);

    service.onEvent(
        event("MemberKicked", Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked)
        .containsExactlyInAnyOrder("s1 /topic/servers/" + server, "s1 /topic/channels/" + channel);
    assertThat(reasons).containsOnly("MemberKicked");
    assertThat(checks).isZero();
    assertThat(registry.bySession("s1"))
        .extracting(SessionSubscription::destination)
        .containsExactly("/user/queue/events");
  }

  // Kịch bản: đổi role nhưng user vẫn xem được kênh
  // Input: MemberRolesChanged của user đang sub topic kênh, checker cho phép
  // Output: không thu hồi gì
  @Test
  void roleChangeKeepsSubscriptionWhenStillAllowed() {
    sub("s1", user, "/topic/channels/" + channel);

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).isEmpty();
    assertThat(registry.exists("s1", "/topic/channels/" + channel)).isTrue();
  }

  // Kịch bản: quyền của kênh đổi và một trong hai người mất VIEW_CHANNEL
  // Input: ChannelPermissionsChanged, hai user cùng sub kênh, checker từ chối một user
  // Output: chỉ session của user bị từ chối bị thu hồi
  @Test
  void roleChangeRevokesWhenViewLost() {
    sub("s1", user, "/topic/channels/" + channel);
    sub("s2", other, "/topic/channels/" + channel);
    denied.add(user);

    service.onEvent(
        event(
            "ChannelPermissionsChanged",
            Map.of("serverId", server.toString(), "channelId", channel.toString())));

    assertThat(revoked).containsExactly("s1 /topic/channels/" + channel);
  }

  // Kịch bản: role của một server đổi mà event không chỉ rõ kênh nào
  // Input: RoleUpdated của server, hai user sub kênh server đó, một user sub kênh server khác
  // Output: chỉ re-check kênh của server đó, người bị từ chối bị thu hồi, server khác không đụng
  @Test
  void roleUpdatedRechecksOnlyChannelsOfThatServer() {
    UUID otherServer = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    sub("s1", user, "/topic/channels/" + channel);
    sub("s2", other, "/topic/channels/" + UUID.randomUUID());
    sub("s3", third, "/topic/channels/" + UUID.randomUUID(), otherServer);
    sub("s2", other, "/topic/servers/" + server);
    denied.addAll(List.of(other, third));

    service.onEvent(event("RoleUpdated", Map.of("serverId", server.toString(), "roleId", "r")));

    assertThat(revoked).hasSize(1).first().asString().startsWith("s2 /topic/channels/");
    assertThat(checks).isEqualTo(2);
    assertThat(registry.bySession("s3")).hasSize(1);
  }

  // Kịch bản: server bị xóa
  // Input: ServerDeleted, hai user sub topic server và kênh của nó, một user sub server khác
  // Output: mọi subscription của server bị thu hồi không hỏi checker, server khác còn nguyên
  @Test
  void serverDeletedRevokesEverySubscriptionOfServer() {
    UUID otherServer = UUID.randomUUID();
    sub("s1", user, "/topic/servers/" + server);
    sub("s2", other, "/topic/servers/" + server);
    sub("s2", other, "/topic/channels/" + channel);
    sub("s3", user, "/topic/servers/" + otherServer, otherServer);

    service.onEvent(event("ServerDeleted", Map.of("serverId", server.toString())));

    assertThat(revoked)
        .containsExactlyInAnyOrder(
            "s1 /topic/servers/" + server,
            "s2 /topic/servers/" + server,
            "s2 /topic/channels/" + channel);
    assertThat(reasons).containsOnly("ServerDeleted");
    assertThat(checks).isZero();
    assertThat(registry.bySession("s3")).hasSize(1);
  }

  // Kịch bản: kênh bị xóa
  // Input: ChannelDeleted, user sub kênh đó và một kênh khác, checker cho phép tất cả
  // Output: chỉ subscription kênh bị xóa bị thu hồi
  @Test
  void channelDeletedRevokesOnlyThatChannel() {
    UUID keep = UUID.randomUUID();
    sub("s1", user, "/topic/channels/" + channel);
    sub("s1", user, "/topic/channels/" + keep);

    service.onEvent(
        event(
            "ChannelDeleted",
            Map.of("serverId", server.toString(), "channelId", channel.toString())));

    assertThat(revoked).containsExactly("s1 /topic/channels/" + channel);
  }

  // Kịch bản: user khác bị kick, người còn lại cùng server
  // Input: MemberKicked của user, người kia cùng sub server và kênh, user còn sub server khác
  // Output: chỉ subscription của user ở server đó bị thu hồi, không gọi checker
  @Test
  void otherUsersAndServersAreUntouched() {
    UUID otherServer = UUID.randomUUID();
    sub("s1", user, "/topic/servers/" + server);
    sub("s1", user, "/topic/servers/" + otherServer, otherServer);
    sub("s2", other, "/topic/servers/" + server);
    sub("s2", other, "/topic/channels/" + channel);

    service.onEvent(
        event("MemberKicked", Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).containsExactly("s1 /topic/servers/" + server);
    assertThat(registry.bySession("s2")).hasSize(2);
    assertThat(registry.bySession("s1")).hasSize(1);
    assertThat(checks).isZero();
  }

  // Kịch bản: checker lỗi hạ tầng trong lúc re-check
  // Input: MemberRolesChanged của user đang sub hai kênh, checker ném AccessCheckUnavailable
  // Output: cả hai bị thu hồi (fail-closed)
  @Test
  void checkerFailureRevokesFailClosed() {
    sub("s1", user, "/topic/channels/" + channel);
    sub("s1", user, "/topic/channels/" + UUID.randomUUID());
    failing = true;

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).hasSize(2);
    assertThat(registry.bySession("s1")).isEmpty();
  }

  // Kịch bản: checker lỗi ở lần gọi đầu khi re-check nhiều subscription
  // Input: MemberRolesChanged của user có 3 subscription kênh, checker ném AccessCheckUnavailable
  // Output: cả 3 bị thu hồi và checker chỉ được gọi đúng một lần
  @Test
  void firstCheckerFailureRevokesRestWithoutMoreCalls() {
    sub("s1", user, "/topic/channels/" + channel);
    sub("s1", user, "/topic/channels/" + UUID.randomUUID());
    sub("s1", user, "/topic/channels/" + UUID.randomUUID());
    failing = true;

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).hasSize(3);
    assertThat(checks).isEqualTo(1);
  }

  // Kịch bản: user rời server
  // Input: MemberLeft, user sub topic server và kênh của nó
  // Output: cả hai bị thu hồi với lý do MemberLeft, checker không bị gọi
  @Test
  void memberLeftRevokesWithoutChecker() {
    sub("s1", user, "/topic/servers/" + server);
    sub("s1", user, "/topic/channels/" + channel);

    service.onEvent(
        event("MemberLeft", Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).hasSize(2);
    assertThat(reasons).containsOnly("MemberLeft");
    assertThat(checks).isZero();
  }

  // Kịch bản: trong lúc re-check, client subscribe lại cùng id sang destination khác
  // Input: MemberRolesChanged, checker từ chối và thay subscription sub-1 bằng kênh khác giữa chừng
  // Output: không thu hồi gì, subscription mới còn nguyên trong sổ
  @Test
  void resubscribedSameIdIsNotWiped() {
    registry.add(
        new SessionSubscription("s1", user, "sub-1", "/topic/channels/" + channel, server));
    denied.add(user);
    SessionSubscription replacement =
        new SessionSubscription(
            "s1", user, "sub-1", "/topic/channels/" + UUID.randomUUID(), server);
    onCheck =
        () -> {
          registry.remove("s1", "sub-1");
          registry.add(replacement);
        };

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).isEmpty();
    assertThat(registry.bySession("s1")).containsExactly(replacement);
  }
}

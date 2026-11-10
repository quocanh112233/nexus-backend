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
            public Optional<ChannelScope> canViewChannel(UUID userId, UUID channelId) {
              return check(userId) ? Optional.of(new ChannelScope(server)) : Optional.empty();
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

  // Kịch bản: user vừa bị kick khỏi server vừa có subscription kênh DM (serverId null)
  // Input: MemberKicked, ServerDeleted, RoleUpdated và MemberRolesChanged của server đó
  // Output: không thu hồi gì, không hỏi checker, sub DM còn trong sổ
  @Test
  void serverEventsDoNotTouchDmSubscription() {
    String dm = "/topic/channels/" + channel;
    sub("s1", user, dm, null);
    Map<String, Object> userServer =
        Map.of("serverId", server.toString(), "userId", user.toString());

    service.onEvent(event("MemberKicked", userServer));
    service.onEvent(event("ServerDeleted", Map.of("serverId", server.toString())));
    service.onEvent(event("RoleUpdated", Map.of("serverId", server.toString())));
    service.onEvent(event("MemberRolesChanged", userServer));

    assertThat(revoked).isEmpty();
    assertThat(checks).isZero();
    assertThat(registry.exists("s1", dm)).isTrue();
  }

  // Kịch bản: hết bạn thì sub DM vẫn được đọc
  // Input: sub DM của user, FriendRemoved của user và bạn
  // Output: không thu hồi gì, không hỏi checker, sub DM còn trong sổ
  @Test
  void friendRemovedDoesNotRevokeDmSubscription() {
    String dm = "/topic/channels/" + channel;
    sub("s1", user, dm, null);

    service.onEvent(
        event("FriendRemoved", Map.of("userId", user.toString(), "friendId", other.toString())));

    assertThat(revoked).isEmpty();
    assertThat(checks).isZero();
    assertThat(registry.exists("s1", dm)).isTrue();
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

  // Kịch bản: re-check lô hỗn hợp, user bị từ chối và user được giữ
  // Input: ChannelPermissionsChanged, hai user cùng sub kênh, checker từ chối một user
  // Output: user bị từ chối bị xóa, user còn lại giao được và không còn bị tạm dừng
  @Test
  void mixedBatchResumesAllowedAndRemovesDenied() {
    sub("s1", user, "/topic/channels/" + channel);
    sub("s2", other, "/topic/channels/" + channel);
    denied.add(user);

    service.onEvent(
        event(
            "ChannelPermissionsChanged",
            Map.of("serverId", server.toString(), "channelId", channel.toString())));

    assertThat(registry.exists("s1", "/topic/channels/" + channel)).isFalse();
    assertThat(registry.isDeliverable("s2", "/topic/channels/" + channel)).isTrue();
  }

  // Kịch bản: checker lỗi hạ tầng giữa lô
  // Input: MemberRolesChanged, user có 3 sub kênh, checker cho phép lần đầu rồi ném Unavailable
  // Output: sub đầu giao được, hai sub còn lại bị thu hồi, không sub nào bị tạm dừng mãi
  @Test
  void unavailableMidBatchRevokesRestAndLeavesNoneSuspended() {
    for (int i = 0; i < 3; i++) {
      sub("s1", user, "/topic/channels/" + UUID.randomUUID());
    }
    onCheck =
        () -> {
          if (checks == 2) {
            failing = true;
          }
        };

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).hasSize(2);
    assertThat(checks).isEqualTo(2);
    assertThat(registry.bySession("s1"))
        .hasSize(1)
        .allMatch(x -> registry.isDeliverable(x.sessionId(), x.subscriptionId()));
  }

  // Kịch bản: checker ném lỗi bất ngờ với một subscription
  // Input: MemberRolesChanged, user có 2 sub kênh, checker ném IllegalStateException ở lần đầu
  // Output: sub lỗi bị thu hồi, sub kia được check tiếp và giao được, không sub nào bị tạm dừng
  @Test
  void unexpectedErrorRevokesThatSubscriptionOnly() {
    sub("s1", user, "/topic/channels/" + channel);
    sub("s1", user, "/topic/channels/" + UUID.randomUUID());
    onCheck =
        () -> {
          if (checks == 1) {
            throw new IllegalStateException("boom");
          }
        };

    service.onEvent(
        event(
            "MemberRolesChanged",
            Map.of("serverId", server.toString(), "userId", user.toString())));

    assertThat(revoked).hasSize(1);
    assertThat(checks).isEqualTo(2);
    assertThat(registry.bySession("s1"))
        .hasSize(1)
        .allMatch(x -> registry.isDeliverable(x.sessionId(), x.subscriptionId()));
  }

  // Kịch bản: trong lúc re-check, sub bị thay bằng giá trị khác và checker cho phép
  // Input: MemberRolesChanged, sub-1 bị thay cùng id khác destination giữa chừng, checker cho phép
  // Output: sub mới còn trong sổ và giao được, không bị mở lại hay xóa nhầm
  @Test
  void replacedDuringAllowedRecheckStaysDeliverable() {
    registry.add(
        new SessionSubscription("s1", user, "sub-1", "/topic/channels/" + channel, server));
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
    assertThat(registry.isDeliverable("s1", "sub-1")).isTrue();
  }

  // Kịch bản: sub đang tạm dừng rồi bị xóa và đăng ký lại đúng giá trị cũ
  // Input: suspend sub-1, remove sub-1, add lại cùng giá trị
  // Output: sub mới giao được ngay vì lần xóa đã dọn tạm dừng cũ
  @Test
  void removalClearsSuspension() {
    SessionSubscription s =
        new SessionSubscription("s1", user, "sub-1", "/topic/channels/" + channel, server);
    registry.add(s);
    registry.suspend(List.of(s));
    assertThat(registry.isDeliverable("s1", "sub-1")).isFalse();

    registry.remove("s1", "sub-1");
    registry.add(s);

    assertThat(registry.isDeliverable("s1", "sub-1")).isTrue();
  }
}

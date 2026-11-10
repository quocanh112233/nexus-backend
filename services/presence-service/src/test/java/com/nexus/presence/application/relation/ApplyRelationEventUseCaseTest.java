package com.nexus.presence.application.relation;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.presence.FakeSessionStore;
import com.nexus.presence.application.presence.QueryPresenceUseCase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra áp event community và bạn bè vào projection quan hệ bằng projection giả
class ApplyRelationEventUseCaseTest {

  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final ApplyRelationEventUseCase useCase = new ApplyRelationEventUseCase(projection);
  private final UUID user = UUID.randomUUID();
  private final UUID server = UUID.randomUUID();

  // Kịch bản: user đã loaded tham gia server
  // Input: MemberJoined
  // Output: serversOf(user) chứa server và membersOf(server) chứa user
  @Test
  void memberJoinedAddsServerForLoadedUser() {
    projection.load(user, Set.of(), Set.of(), projection.revision(user));

    useCase.execute("MemberJoined", server, user);

    assertThat(projection.serversOf(user)).containsExactly(server);
    assertThat(projection.membersOf(server)).containsExactly(user);
  }

  // Kịch bản: user đã loaded rời, bị kick hoặc bị ban khỏi server
  // Input: MemberLeft, MemberKicked, MemberBanned
  // Output: serversOf(user) không còn server và membersOf(server) rỗng
  @ParameterizedTest
  @ValueSource(strings = {"MemberLeft", "MemberKicked", "MemberBanned"})
  void memberRemovalEventsRemoveServer(String eventType) {
    projection.load(user, Set.of(server), Set.of(), projection.revision(user));

    useCase.execute(eventType, server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }

  // Kịch bản: event đến user chưa loaded
  // Input: MemberJoined cho user chưa loaded
  // Output: serversOf(user) rỗng nhưng membersOf(server) vẫn chứa user
  @Test
  void eventForUnloadedUserIsIgnoredForUserSetButTracksServerMembers() {
    useCase.execute("MemberJoined", server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).containsExactly(user);
  }

  // Kịch bản: xóa server có hai member đã loaded
  // Input: ServerDeleted
  // Output: cả hai user mất server, membersOf(server) rỗng, server khác còn nguyên
  @Test
  void serverDeletedRemovesServerFromEveryMember() {
    UUID other = UUID.randomUUID();
    UUID keep = UUID.randomUUID();
    projection.load(user, Set.of(server, keep), Set.of(), projection.revision(user));
    projection.load(other, Set.of(server), Set.of(), projection.revision(other));

    useCase.execute("ServerDeleted", server, null);

    assertThat(projection.serversOf(user)).containsExactly(keep);
    assertThat(projection.serversOf(other)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }

  // Kịch bản: hai user chỉ chung server bị xóa, cả hai vừa nạp xong ngay trước event ServerDeleted
  // Input: load X và Y cùng {server}, ServerDeleted, rồi QueryPresenceUseCase theo hai chiều
  // Output: X không thấy Y, Y không thấy X và forward set của cả hai không còn server
  @Test
  void usersLoadedJustBeforeServerDeletedLoseVisibility() {
    UUID x = UUID.randomUUID();
    UUID y = UUID.randomUUID();
    projection.load(x, Set.of(server), Set.of(), projection.revision(x));
    projection.load(y, Set.of(server), Set.of(), projection.revision(y));

    useCase.execute("ServerDeleted", server, null);

    var query =
        new QueryPresenceUseCase(
            new EnsureLoadedUseCase(projection, id -> Set.of(), id -> Set.of()),
            projection,
            new FakeSessionStore());
    assertThat(projection.serversOf(x)).doesNotContain(server);
    assertThat(projection.serversOf(y)).doesNotContain(server);
    assertThat(query.execute(x, List.of(y))).isEmpty();
    assertThat(query.execute(y, List.of(x))).isEmpty();
  }

  // Kịch bản: cùng event giao hai lần
  // Input: MemberJoined hai lần rồi MemberLeft hai lần
  // Output: sau join tập có đúng 1 server, sau left tập rỗng, không lỗi
  @Test
  void duplicateEventIsIdempotent() {
    projection.load(user, Set.of(), Set.of(), projection.revision(user));

    useCase.execute("MemberJoined", server, user);
    useCase.execute("MemberJoined", server, user);
    assertThat(projection.serversOf(user)).containsExactly(server);

    useCase.execute("MemberLeft", server, user);
    useCase.execute("MemberLeft", server, user);
    assertThat(projection.serversOf(user)).isEmpty();
  }

  // Kịch bản: event lạ
  // Input: eventType Whatever
  // Output: projection không đổi
  @Test
  void unknownEventTypeIsIgnored() {
    projection.load(user, Set.of(), Set.of(), projection.revision(user));

    useCase.execute("Whatever", server, user);

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(server)).isEmpty();
  }

  // Kịch bản: hai user đã loaded được chấp nhận kết bạn
  // Input: executeFriend("FriendRequestAccepted", user, peer)
  // Output: mỗi bên có bên kia trong friendsOf
  @Test
  void friendRequestAcceptedAddsBothSides() {
    UUID peer = UUID.randomUUID();
    loadEmpty(user);
    loadEmpty(peer);

    useCase.executeFriend("FriendRequestAccepted", user, peer);

    assertThat(projection.friendsOf(user)).containsExactly(peer);
    assertThat(projection.friendsOf(peer)).containsExactly(user);
  }

  // Kịch bản: hai bạn đã loaded hủy kết bạn, event giao hai lần
  // Input: executeFriend("FriendRemoved", peer, user) hai lần
  // Output: cả hai tập bạn rỗng, không lỗi
  @Test
  void friendRemovedRemovesBothSidesIdempotently() {
    UUID peer = UUID.randomUUID();
    projection.load(user, Set.of(), Set.of(peer), projection.revision(user));
    projection.load(peer, Set.of(), Set.of(user), projection.revision(peer));

    useCase.executeFriend("FriendRemoved", peer, user);
    useCase.executeFriend("FriendRemoved", peer, user);

    assertThat(projection.friendsOf(user)).isEmpty();
    assertThat(projection.friendsOf(peer)).isEmpty();
  }

  // Kịch bản: FriendRequestAccepted giao lặp lại
  // Input: executeFriend("FriendRequestAccepted", user, peer) hai lần
  // Output: mỗi bên đúng một bạn
  @Test
  void duplicateFriendRequestAcceptedIsNoOp() {
    UUID peer = UUID.randomUUID();
    loadEmpty(user);
    loadEmpty(peer);

    useCase.executeFriend("FriendRequestAccepted", user, peer);
    useCase.executeFriend("FriendRequestAccepted", user, peer);

    assertThat(projection.friendsOf(user)).containsExactly(peer);
    assertThat(projection.friendsOf(peer)).containsExactly(user);
  }

  // Kịch bản: giới hạn đã chấp nhận, FriendRemoved rồi Accepted cũ giao trễ
  // Input: Accepted, FriendRemoved, rồi Accepted lặp lại đến muộn
  // Output: hai bên lại là bạn (lệch tới khi loaded hết hạn, xem ponytail ở executeFriend)
  @Test
  void lateDuplicateAcceptedAfterRemovedLeavesFriendPresent() {
    UUID peer = UUID.randomUUID();
    loadEmpty(user);
    loadEmpty(peer);

    useCase.executeFriend("FriendRequestAccepted", user, peer);
    useCase.executeFriend("FriendRemoved", user, peer);
    useCase.executeFriend("FriendRequestAccepted", user, peer);

    assertThat(projection.isFriend(user, peer)).isTrue();
    assertThat(projection.isFriend(peer, user)).isTrue();
  }

  // Kịch bản: event bạn bè đến khi chưa user nào loaded
  // Input: executeFriend("FriendRequestAccepted", user, peer) trên projection trống
  // Output: tập bạn rỗng nhưng revision của cả hai tăng
  @Test
  void friendEventForUnloadedUsersOnlyBumpsRevision() {
    UUID peer = UUID.randomUUID();
    long userRev = projection.revision(user);
    long peerRev = projection.revision(peer);

    useCase.executeFriend("FriendRequestAccepted", user, peer);

    assertThat(projection.friendsOf(user)).isEmpty();
    assertThat(projection.revision(user)).isGreaterThan(userRev);
    assertThat(projection.revision(peer)).isGreaterThan(peerRev);
  }

  // Kịch bản: event bạn bè loại lạ
  // Input: executeFriend("FriendRequestSent", user, peer) với hai user loaded
  // Output: tập bạn và revision không đổi
  @Test
  void unknownFriendEventIsIgnored() {
    UUID peer = UUID.randomUUID();
    loadEmpty(user);
    loadEmpty(peer);
    long rev = projection.revision(user);

    useCase.executeFriend("FriendRequestSent", user, peer);

    assertThat(projection.friendsOf(user)).isEmpty();
    assertThat(projection.revision(user)).isEqualTo(rev);
  }

  // Nạp user với tập server và bạn rỗng
  private void loadEmpty(UUID id) {
    projection.load(id, Set.of(), Set.of(), projection.revision(id));
  }
}

package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.application.social.SocialFakes.FakeFriendships;
import com.nexus.messaging.application.social.SocialFakes.FakeRequests;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra truy vấn danh sách bạn và lời mời đang chờ
class ListSocialQueriesTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserId me = new UserId(UUID.randomUUID());
  private final UserId other = new UserId(UUID.randomUUID());

  // Kịch bản: liệt kê bạn bè của mình
  // Input: friendship (me, other) và một cặp không liên quan
  // Output: danh sách chỉ có id của other
  @Test
  void listsFriendIds() {
    FakeFriendships friendships = new FakeFriendships();
    friendships.insertIfAbsent(UserPair.of(me, other), NOW);
    friendships.insertIfAbsent(
        UserPair.of(new UserId(UUID.randomUUID()), new UserId(UUID.randomUUID())), NOW);

    assertThat(new ListFriendsQuery(friendships).execute(me)).containsExactly(other.value());
  }

  // Kịch bản: liệt kê lời mời đến và đi
  // Input: một request me->other đang chờ
  // Output: outgoing của me có 1 view đúng trường, incoming của me rỗng, incoming của other có 1
  @Test
  void listsIncomingAndOutgoing() {
    FakeRequests requests = new FakeRequests();
    FriendRequest sent = requests.put(FriendRequest.send(FriendRequestId.newId(), me, other, NOW));
    ListFriendRequestsQuery query = new ListFriendRequestsQuery(requests);

    assertThat(query.outgoing(me))
        .singleElement()
        .isEqualTo(
            new FriendRequestView(sent.id().value(), me.value(), other.value(), "PENDING", NOW));
    assertThat(query.incoming(me)).isEmpty();
    assertThat(query.incoming(other)).hasSize(1);
  }
}

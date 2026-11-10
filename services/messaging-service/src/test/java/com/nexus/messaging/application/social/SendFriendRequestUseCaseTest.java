package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.application.shared.UserNotFoundException;
import com.nexus.messaging.application.social.SocialFakes.FakeFriendships;
import com.nexus.messaging.application.social.SocialFakes.FakeRequests;
import com.nexus.messaging.application.social.SocialFakes.FakeTx;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.AlreadyFriendsException;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestAccepted;
import com.nexus.messaging.domain.social.FriendRequestExistsException;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestSent;
import com.nexus.messaging.domain.social.FriendRequestStatus;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra các nhánh gửi lời mời kết bạn: gửi mới, auto-accept, trùng, đã là bạn, race
class SendFriendRequestUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserId me = new UserId(UUID.randomUUID());
  private final UserId target = new UserId(UUID.randomUUID());
  private final FakeRequests requests = new FakeRequests();
  private final FakeFriendships friendships = new FakeFriendships();
  private final FakeTx tx = new FakeTx();
  private final List<DomainEvent> events = SocialFakes.eventSink();
  private final Set<UserId> known = new HashSet<>(Set.of(me, target));
  private final SendFriendRequestUseCase useCase =
      new SendFriendRequestUseCase(
          known::contains,
          requests,
          friendships,
          events::add,
          Clock.fixed(NOW, ZoneOffset.UTC),
          tx.template());

  // Kịch bản: gửi lời mời tới người chưa có quan hệ
  // Input: execute(me, target) khi không có pending và chưa là bạn
  // Output: view PENDING, lưu 1 request, phát đúng 1 FriendRequestSent, 1 commit
  @Test
  void sendsNewRequest() {
    FriendRequestView view = useCase.execute(me, target);

    assertThat(view.status()).isEqualTo("PENDING");
    assertThat(view.requesterId()).isEqualTo(me.value());
    assertThat(view.addresseeId()).isEqualTo(target.value());
    assertThat(requests.byId).hasSize(1);
    assertThat(events).singleElement().isInstanceOf(FriendRequestSent.class);
    assertThat(tx.commits).isEqualTo(1);
    assertThat(friendships.pairs).isEmpty();
  }

  // Kịch bản: đối phương đã gửi lời mời cho mình, mình gửi lại thì tự chấp nhận
  // Input: có PENDING target->me, execute(me, target)
  // Output: request ACCEPTED, đúng 1 friendship, chỉ event FriendRequestAccepted
  @Test
  void reversePendingIsAutoAccepted() {
    FriendRequest incoming =
        requests.put(FriendRequest.send(FriendRequestId.newId(), target, me, NOW));

    FriendRequestView view = useCase.execute(me, target);

    assertThat(view.id()).isEqualTo(incoming.id().value());
    assertThat(view.status()).isEqualTo("ACCEPTED");
    assertThat(friendships.pairs).containsOnlyKeys(UserPair.of(me, target));
    assertThat(events).singleElement().isInstanceOf(FriendRequestAccepted.class);
    assertThat(requests.byId).hasSize(1);
  }

  // Kịch bản: mình đã gửi lời mời cùng chiều rồi gửi lại
  // Input: có PENDING me->target, execute(me, target)
  // Output: FriendRequestExistsException, không event, không friendship, mọi tx đều rollback
  @Test
  void sameDirectionPendingIsConflict() {
    requests.put(FriendRequest.send(FriendRequestId.newId(), me, target, NOW));

    assertThatThrownBy(() -> useCase.execute(me, target))
        .isInstanceOf(FriendRequestExistsException.class);

    assertThat(events).isEmpty();
    assertThat(friendships.pairs).isEmpty();
    assertThat(tx.commits).isZero();
  }

  // Kịch bản: hai người đã là bạn
  // Input: friendship tồn tại, execute(me, target)
  // Output: AlreadyFriendsException, không request, không event
  @Test
  void alreadyFriendsIsRejected() {
    friendships.insertIfAbsent(UserPair.of(me, target), NOW);

    assertThatThrownBy(() -> useCase.execute(me, target))
        .isInstanceOf(AlreadyFriendsException.class);

    assertThat(requests.byId).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: người nhận không tồn tại ở Identity
  // Input: execute(me, unknown)
  // Output: UserNotFoundException, không mở transaction, không ghi gì
  @Test
  void unknownTargetWritesNothing() {
    UserId unknown = new UserId(UUID.randomUUID());

    assertThatThrownBy(() -> useCase.execute(me, unknown))
        .isInstanceOf(UserNotFoundException.class);

    assertThat(tx.commits + tx.rollbacks).isZero();
    assertThat(requests.byId).isEmpty();
    assertThat(friendships.pairs).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: Identity không trả lời được
  // Input: UserDirectory ném DependencyUnavailableException
  // Output: lỗi lan ra nguyên vẹn, không mở transaction
  @Test
  void identityDownPropagates() {
    SendFriendRequestUseCase down =
        new SendFriendRequestUseCase(
            id -> {
              throw new DependencyUnavailableException("down", null);
            },
            requests,
            friendships,
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC),
            tx.template());

    assertThatThrownBy(() -> down.execute(me, target))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(tx.commits + tx.rollbacks).isZero();
  }

  // Kịch bản: tự gửi lời mời cho chính mình
  // Input: execute(me, me)
  // Output: DomainValidationException, không ghi gì
  @Test
  void selfRequestIsRejected() {
    assertThatThrownBy(() -> useCase.execute(me, me)).isInstanceOf(DomainValidationException.class);

    assertThat(requests.byId).isEmpty();
  }

  // Kịch bản: race, đối phương commit lời mời ngược chiều giữa lúc mình findPending và save
  // Input: lần 1 save trượt index unique vì có PENDING target->me vừa xuất hiện
  // Output: lần 1 rollback, lần 2 tx mới auto-accept: 1 friendship, 1 event Accepted, không Sent
  @Test
  void raceOnReverseRetriesInFreshTransactionAndAutoAccepts() {
    requests.racedOnNextSave = FriendRequest.send(FriendRequestId.newId(), target, me, NOW);

    FriendRequestView view = useCase.execute(me, target);

    assertThat(view.status()).isEqualTo("ACCEPTED");
    assertThat(tx.rollbacks).isEqualTo(1);
    assertThat(tx.commits).isEqualTo(1);
    assertThat(friendships.pairs).containsOnlyKeys(UserPair.of(me, target));
    assertThat(events).singleElement().isInstanceOf(FriendRequestAccepted.class);
    assertThat(requests.byId.values())
        .singleElement()
        .extracting(FriendRequest::status)
        .isEqualTo(FriendRequestStatus.ACCEPTED);
  }

  // Kịch bản: race cùng chiều, request của chính mình xuất hiện giữa chừng
  // Input: lần 1 save trượt index vì có PENDING me->target, lần 2 thấy cùng chiều
  // Output: FriendRequestExistsException sau đúng 2 lần thử (2 rollback), không event
  @Test
  void raceOnSameDirectionGivesUpAfterTwoAttempts() {
    requests.racedOnNextSave = FriendRequest.send(FriendRequestId.newId(), me, target, NOW);

    assertThatThrownBy(() -> useCase.execute(me, target))
        .isInstanceOf(FriendRequestExistsException.class);

    assertThat(tx.rollbacks).isEqualTo(2);
    assertThat(events).isEmpty();
  }
}

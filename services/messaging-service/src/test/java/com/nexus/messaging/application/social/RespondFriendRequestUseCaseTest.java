package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.application.social.SocialFakes.FakeFriendships;
import com.nexus.messaging.application.social.SocialFakes.FakeRequests;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendActionForbiddenException;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestAccepted;
import com.nexus.messaging.domain.social.FriendRequestDeclined;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestNotPendingException;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

// Kiểm tra chấp nhận và từ chối lời mời: vai, trạng thái, friendship và event
class RespondFriendRequestUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserId sender = new UserId(UUID.randomUUID());
  private final UserId receiver = new UserId(UUID.randomUUID());
  private final FakeRequests requests = new FakeRequests();
  private final FakeFriendships friendships = new FakeFriendships();
  private final List<DomainEvent> events = SocialFakes.eventSink();
  private final RespondFriendRequestUseCase useCase =
      new RespondFriendRequestUseCase(
          requests, friendships, events::add, Clock.fixed(NOW, ZoneOffset.UTC));
  private final FriendRequest pending =
      requests.put(FriendRequest.send(FriendRequestId.newId(), sender, receiver, NOW));

  // Kịch bản: người nhận chấp nhận lời mời đang chờ
  // Input: accept(receiver, id)
  // Output: view ACCEPTED, 1 friendship của cặp, event FriendRequestAccepted
  @Test
  void acceptCreatesFriendship() {
    FriendRequestView view = useCase.accept(receiver, pending.id());

    assertThat(view.status()).isEqualTo("ACCEPTED");
    assertThat(friendships.pairs).containsOnlyKeys(UserPair.of(sender, receiver));
    assertThat(events).singleElement().isInstanceOf(FriendRequestAccepted.class);
  }

  // Kịch bản: người nhận từ chối lời mời
  // Input: decline(receiver, id)
  // Output: view DECLINED, không friendship, event FriendRequestDeclined
  @Test
  void declineDoesNotCreateFriendship() {
    FriendRequestView view = useCase.decline(receiver, pending.id());

    assertThat(view.status()).isEqualTo("DECLINED");
    assertThat(friendships.pairs).isEmpty();
    assertThat(events).singleElement().isInstanceOf(FriendRequestDeclined.class);
  }

  // Kịch bản: người gửi tự chấp nhận hoặc từ chối lời mời của mình
  // Input: accept và decline bởi sender
  // Output: FriendActionForbiddenException, không friendship, không event
  @Test
  void wrongRoleIsForbidden() {
    assertThatThrownBy(() -> useCase.accept(sender, pending.id()))
        .isInstanceOf(FriendActionForbiddenException.class);
    assertThatThrownBy(() -> useCase.decline(sender, pending.id()))
        .isInstanceOf(FriendActionForbiddenException.class);

    assertThat(friendships.pairs).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: người lạ hoặc id không tồn tại
  // Input: accept bởi người không liên quan; accept id lạ
  // Output: FriendNotFoundException cả hai, không ghi gì
  @Test
  void unrelatedActorOrMissingIsNotFound() {
    UserId stranger = new UserId(UUID.randomUUID());

    assertThatThrownBy(() -> useCase.accept(stranger, pending.id()))
        .isInstanceOf(FriendNotFoundException.class);
    assertThatThrownBy(() -> useCase.accept(receiver, FriendRequestId.newId()))
        .isInstanceOf(FriendNotFoundException.class);

    assertThat(friendships.pairs).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: người gửi hủy trước, rồi người nhận chấp nhận
  // Input: cancel(sender) rồi accept(receiver) trên cùng id
  // Output: FriendRequestNotPendingException, không friendship
  @Test
  void acceptAfterCancelIsNotPending() {
    new CancelFriendRequestUseCase(requests, events::add, Clock.fixed(NOW, ZoneOffset.UTC))
        .execute(sender, pending.id());
    events.clear();

    assertThatThrownBy(() -> useCase.accept(receiver, pending.id()))
        .isInstanceOf(FriendRequestNotPendingException.class);

    assertThat(friendships.pairs).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: khóa lạc quan trượt khi lưu (có người đổi trạng thái song song)
  // Input: save ném OptimisticLockingFailureException
  // Output: ngoại lệ lan ra nguyên vẹn, không friendship, không event
  @Test
  void staleVersionPropagates() {
    requests.failNextSave = new OptimisticLockingFailureException("stale");

    assertThatThrownBy(() -> useCase.accept(receiver, pending.id()))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(friendships.pairs).isEmpty();
    assertThat(events).isEmpty();
  }
}

package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.application.social.SocialFakes.FakeRequests;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendActionForbiddenException;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestCanceled;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestNotPendingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra hủy lời mời: đúng vai, sai vai, sai trạng thái, không liên quan
class CancelFriendRequestUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserId sender = new UserId(UUID.randomUUID());
  private final UserId receiver = new UserId(UUID.randomUUID());
  private final FakeRequests requests = new FakeRequests();
  private final List<DomainEvent> events = SocialFakes.eventSink();
  private final CancelFriendRequestUseCase useCase =
      new CancelFriendRequestUseCase(requests, events::add, Clock.fixed(NOW, ZoneOffset.UTC));
  private final FriendRequest pending =
      requests.put(FriendRequest.send(FriendRequestId.newId(), sender, receiver, NOW));

  // Kịch bản: người gửi hủy lời mời đang chờ
  // Input: execute(sender, id)
  // Output: view CANCELED, event FriendRequestCanceled
  @Test
  void senderCancels() {
    FriendRequestView view = useCase.execute(sender, pending.id());

    assertThat(view.status()).isEqualTo("CANCELED");
    assertThat(events).singleElement().isInstanceOf(FriendRequestCanceled.class);
  }

  // Kịch bản: người nhận thử hủy lời mời
  // Input: execute(receiver, id)
  // Output: FriendActionForbiddenException, không event
  @Test
  void addresseeCannotCancel() {
    assertThatThrownBy(() -> useCase.execute(receiver, pending.id()))
        .isInstanceOf(FriendActionForbiddenException.class);

    assertThat(events).isEmpty();
  }

  // Kịch bản: hủy lời mời đã kết thúc
  // Input: execute(sender, id) hai lần
  // Output: lần hai ném FriendRequestNotPendingException, chỉ 1 event
  @Test
  void cancelTwiceIsNotPending() {
    useCase.execute(sender, pending.id());

    assertThatThrownBy(() -> useCase.execute(sender, pending.id()))
        .isInstanceOf(FriendRequestNotPendingException.class);

    assertThat(events).hasSize(1);
  }

  // Kịch bản: người lạ hoặc id không tồn tại
  // Input: execute(stranger, id) và execute(sender, idLạ)
  // Output: FriendNotFoundException cả hai, không event
  @Test
  void unrelatedOrMissingIsNotFound() {
    UserId stranger = new UserId(UUID.randomUUID());

    assertThatThrownBy(() -> useCase.execute(stranger, pending.id()))
        .isInstanceOf(FriendNotFoundException.class);
    assertThatThrownBy(() -> useCase.execute(sender, FriendRequestId.newId()))
        .isInstanceOf(FriendNotFoundException.class);

    assertThat(events).isEmpty();
  }
}

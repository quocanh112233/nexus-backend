package com.nexus.messaging.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra máy trạng thái lời mời kết bạn
class FriendRequestTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private static final Instant LATER = Instant.parse("2026-11-09T11:00:00Z");
  private final UserId requester = new UserId(UUID.randomUUID());
  private final UserId addressee = new UserId(UUID.randomUUID());

  // Kịch bản: tự mời chính mình
  // Input: send với requester trùng addressee
  // Output: DomainValidationException
  @Test
  void rejectsSelfInvite() {
    assertThatThrownBy(() -> FriendRequest.send(FriendRequestId.newId(), requester, requester, NOW))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: gửi lời mời hợp lệ
  // Input: send requester tới addressee
  // Output: status PENDING, version 0, đúng một sự kiện FriendRequestSent
  @Test
  void sendEmitsSent() {
    FriendRequest request = pending();

    assertThat(request.status()).isEqualTo(FriendRequestStatus.PENDING);
    assertThat(request.version()).isZero();
    assertThat(request.respondedAt()).isNull();
    assertThat(request.pullEvents())
        .containsExactly(new FriendRequestSent(request.id(), requester, addressee));
    assertThat(request.pullEvents()).isEmpty();
  }

  // Kịch bản: addressee chấp nhận
  // Input: accept bởi addressee lúc LATER
  // Output: ACCEPTED, respondedAt đặt, phát FriendRequestAccepted
  @Test
  void acceptByAddressee() {
    FriendRequest request = pending();
    request.pullEvents();

    request.accept(addressee, LATER);

    assertThat(request.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
    assertThat(request.respondedAt()).isEqualTo(LATER);
    assertThat(request.pullEvents())
        .containsExactly(new FriendRequestAccepted(request.id(), requester, addressee));
  }

  // Kịch bản: addressee từ chối
  // Input: decline bởi addressee lúc LATER
  // Output: DECLINED, respondedAt đặt, phát FriendRequestDeclined
  @Test
  void declineByAddressee() {
    FriendRequest request = pending();
    request.pullEvents();

    request.decline(addressee, LATER);

    assertThat(request.status()).isEqualTo(FriendRequestStatus.DECLINED);
    assertThat(request.respondedAt()).isEqualTo(LATER);
    assertThat(request.pullEvents())
        .containsExactly(new FriendRequestDeclined(request.id(), requester, addressee));
  }

  // Kịch bản: requester hủy
  // Input: cancel bởi requester lúc LATER
  // Output: CANCELED, respondedAt đặt, phát FriendRequestCanceled
  @Test
  void cancelByRequester() {
    FriendRequest request = pending();
    request.pullEvents();

    request.cancel(requester, LATER);

    assertThat(request.status()).isEqualTo(FriendRequestStatus.CANCELED);
    assertThat(request.respondedAt()).isEqualTo(LATER);
    assertThat(request.pullEvents())
        .containsExactly(new FriendRequestCanceled(request.id(), requester, addressee));
  }

  // Kịch bản: sai actor khi accept, decline, cancel
  // Input: requester accept/decline, addressee cancel
  // Output: FriendActionForbiddenException và trạng thái vẫn PENDING
  @Test
  void wrongActorForbidden() {
    FriendRequest request = pending();

    assertThatThrownBy(() -> request.accept(requester, LATER))
        .isInstanceOf(FriendActionForbiddenException.class);
    assertThatThrownBy(() -> request.decline(requester, LATER))
        .isInstanceOf(FriendActionForbiddenException.class);
    assertThatThrownBy(() -> request.cancel(addressee, LATER))
        .isInstanceOf(FriendActionForbiddenException.class);
    assertThat(request.status()).isEqualTo(FriendRequestStatus.PENDING);
  }

  // Kịch bản: mọi chuyển từ trạng thái kết thúc
  // Input: lời mời đã ACCEPTED, DECLINED, CANCELED rồi accept/decline/cancel
  // Output: FriendRequestNotPendingException cho cả 9 tổ hợp
  @Test
  void terminalStatesRejectTransitions() {
    for (FriendRequestStatus terminal :
        new FriendRequestStatus[] {
          FriendRequestStatus.ACCEPTED, FriendRequestStatus.DECLINED, FriendRequestStatus.CANCELED
        }) {
      FriendRequest request =
          FriendRequest.rehydrate(
              FriendRequestId.newId(), requester, addressee, terminal, 1, NOW, LATER);

      assertNotPending(request, addressee, FriendRequest::accept);
      assertNotPending(request, addressee, FriendRequest::decline);
      assertNotPending(request, requester, FriendRequest::cancel);
    }
  }

  // Kịch bản: dựng lại từ lưu trữ với trạng thái mâu thuẫn respondedAt
  // Input: PENDING có respondedAt, ACCEPTED thiếu respondedAt
  // Output: DomainValidationException cho cả hai
  @Test
  void rehydrateRejectsInconsistentRespondedAt() {
    FriendRequestId id = FriendRequestId.newId();

    assertThatThrownBy(
            () ->
                FriendRequest.rehydrate(
                    id, requester, addressee, FriendRequestStatus.PENDING, 0, NOW, LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () ->
                FriendRequest.rehydrate(
                    id, requester, addressee, FriendRequestStatus.ACCEPTED, 1, NOW, null))
        .isInstanceOf(DomainValidationException.class);
  }

  // Khẳng định thao tác trên lời mời đã kết thúc bị từ chối
  private void assertNotPending(FriendRequest request, UserId actor, Action action) {
    assertThatThrownBy(() -> action.apply(request, actor, LATER))
        .isInstanceOf(FriendRequestNotPendingException.class);
  }

  // Tạo lời mời đang chờ
  private FriendRequest pending() {
    return FriendRequest.send(FriendRequestId.newId(), requester, addressee, NOW);
  }

  // Thao tác chuyển trạng thái trên lời mời
  private interface Action {
    void apply(FriendRequest request, UserId actor, Instant now);
  }
}

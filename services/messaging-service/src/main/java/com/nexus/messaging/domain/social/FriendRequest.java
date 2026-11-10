package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Aggregate lời mời kết bạn với máy trạng thái PENDING sang trạng thái kết thúc
public final class FriendRequest {

  private final FriendRequestId id;
  private final UserId requester;
  private final UserId addressee;
  private FriendRequestStatus status;
  private final long version;
  private final Instant createdAt;
  private Instant respondedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua send hoặc rehydrate
  private FriendRequest(
      FriendRequestId id,
      UserId requester,
      UserId addressee,
      FriendRequestStatus status,
      long version,
      Instant createdAt,
      Instant respondedAt) {
    this.id = id;
    this.requester = requester;
    this.addressee = addressee;
    this.status = status;
    this.version = version;
    this.createdAt = createdAt;
    this.respondedAt = respondedAt;
  }

  // Gửi lời mời mới ở trạng thái PENDING và phát FriendRequestSent; tự mời mình thì ném lỗi
  public static FriendRequest send(
      FriendRequestId id, UserId requester, UserId addressee, Instant now) {
    if (requester.equals(addressee)) {
      throw new DomainValidationException("cannot send a friend request to yourself");
    }
    FriendRequest request =
        new FriendRequest(id, requester, addressee, FriendRequestStatus.PENDING, 0, now, null);
    request.events.add(new FriendRequestSent(id, requester, addressee));
    return request;
  }

  // Dựng lại lời mời từ lưu trữ, không phát sự kiện; respondedAt lệch trạng thái thì ném lỗi
  public static FriendRequest rehydrate(
      FriendRequestId id,
      UserId requester,
      UserId addressee,
      FriendRequestStatus status,
      long version,
      Instant createdAt,
      Instant respondedAt) {
    if ((status == FriendRequestStatus.PENDING) != (respondedAt == null)) {
      throw new DomainValidationException("respondedAt is inconsistent with status " + status);
    }
    return new FriendRequest(id, requester, addressee, status, version, createdAt, respondedAt);
  }

  // Người nhận chấp nhận lời mời và phát FriendRequestAccepted
  public void accept(UserId actor, Instant now) {
    transition(actor, addressee, FriendRequestStatus.ACCEPTED, now);
    events.add(new FriendRequestAccepted(id, requester, addressee));
  }

  // Người nhận từ chối lời mời và phát FriendRequestDeclined
  public void decline(UserId actor, Instant now) {
    transition(actor, addressee, FriendRequestStatus.DECLINED, now);
    events.add(new FriendRequestDeclined(id, requester, addressee));
  }

  // Người gửi hủy lời mời và phát FriendRequestCanceled
  public void cancel(UserId actor, Instant now) {
    transition(actor, requester, FriendRequestStatus.CANCELED, now);
    events.add(new FriendRequestCanceled(id, requester, addressee));
  }

  // Kiểm tra quyền và trạng thái PENDING rồi chuyển sang trạng thái kết thúc
  private void transition(UserId actor, UserId allowed, FriendRequestStatus target, Instant now) {
    if (!actor.equals(allowed)) {
      throw new FriendActionForbiddenException("actor is not allowed to " + target);
    }
    if (status != FriendRequestStatus.PENDING) {
      throw new FriendRequestNotPendingException("friend request is already " + status);
    }
    this.status = target;
    this.respondedAt = now;
  }

  // Lấy và xóa các sự kiện chưa phát
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Định danh lời mời
  public FriendRequestId id() {
    return id;
  }

  // Người gửi
  public UserId requester() {
    return requester;
  }

  // Người nhận
  public UserId addressee() {
    return addressee;
  }

  // Trạng thái hiện tại
  public FriendRequestStatus status() {
    return status;
  }

  // Phiên bản khóa lạc quan đọc từ lưu trữ, không tự tăng; repository trả bản mới sau save
  public long version() {
    return version;
  }

  // Thời điểm gửi
  public Instant createdAt() {
    return createdAt;
  }

  // Thời điểm phản hồi, null nếu còn chờ
  public Instant respondedAt() {
    return respondedAt;
  }
}

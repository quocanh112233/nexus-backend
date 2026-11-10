package com.nexus.messaging.application.social;

import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case người gửi hủy lời mời kết bạn đang chờ
@Service
public class CancelFriendRequestUseCase {

  private final FriendRequestRepository requests;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận repository, cổng phát sự kiện và đồng hồ qua constructor
  public CancelFriendRequestUseCase(
      FriendRequestRepository requests, DomainEventPublisher eventPublisher, Clock clock) {
    this.requests = requests;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Hủy lời mời; không thấy hoặc không liên quan thì 404, sai vai hoặc trạng thái thì lỗi miền
  @Transactional
  public FriendRequestView execute(UserId actor, FriendRequestId id) {
    FriendRequest request =
        requests.findById(id).orElseThrow(() -> new FriendNotFoundException("request not found"));
    if (!actor.equals(request.requester()) && !actor.equals(request.addressee())) {
      throw new FriendNotFoundException("request not found");
    }
    request.cancel(actor, clock.instant());
    var events = request.pullEvents();
    FriendRequest saved = requests.save(request);
    events.forEach(eventPublisher::publish);
    return FriendRequestView.from(saved);
  }
}

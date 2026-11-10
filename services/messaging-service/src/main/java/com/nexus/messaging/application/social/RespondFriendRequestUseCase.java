package com.nexus.messaging.application.social;

import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case người nhận chấp nhận hoặc từ chối lời mời kết bạn
@Service
public class RespondFriendRequestUseCase {

  private final FriendRequestRepository requests;
  private final FriendshipRepository friendships;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận hai repository, cổng phát sự kiện và đồng hồ qua constructor
  public RespondFriendRequestUseCase(
      FriendRequestRepository requests,
      FriendshipRepository friendships,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.requests = requests;
    this.friendships = friendships;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Chấp nhận lời mời, ghi request, friendship và outbox trong một transaction
  @Transactional
  public FriendRequestView accept(UserId actor, FriendRequestId id) {
    FriendRequest request = load(actor, id);
    request.accept(actor, clock.instant());
    var events = request.pullEvents();
    FriendRequest saved = requests.save(request);
    friendships.insertIfAbsent(
        UserPair.of(request.requester(), request.addressee()), clock.instant());
    events.forEach(eventPublisher::publish);
    return FriendRequestView.from(saved);
  }

  // Từ chối lời mời và phát FriendRequestDeclined
  @Transactional
  public FriendRequestView decline(UserId actor, FriendRequestId id) {
    FriendRequest request = load(actor, id);
    request.decline(actor, clock.instant());
    var events = request.pullEvents();
    FriendRequest saved = requests.save(request);
    events.forEach(eventPublisher::publish);
    return FriendRequestView.from(saved);
  }

  // Tải lời mời, không tồn tại hoặc actor không liên quan thì coi như không thấy
  private FriendRequest load(UserId actor, FriendRequestId id) {
    FriendRequest request =
        requests.findById(id).orElseThrow(() -> new FriendNotFoundException("request not found"));
    if (!actor.equals(request.requester()) && !actor.equals(request.addressee())) {
      throw new FriendNotFoundException("request not found");
    }
    return request;
  }
}

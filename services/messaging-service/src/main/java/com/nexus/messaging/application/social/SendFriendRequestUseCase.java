package com.nexus.messaging.application.social;

import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.port.UserDirectory;
import com.nexus.messaging.application.shared.UserNotFoundException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.AlreadyFriendsException;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestExistsException;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Use case gửi lời mời kết bạn, mời ngược chiều đang chờ thì tự chấp nhận, tối đa 2 lần thử
@Service
public class SendFriendRequestUseCase {

  private final UserDirectory userDirectory;
  private final FriendRequestRepository requests;
  private final FriendshipRepository friendships;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;
  private final TransactionTemplate tx;

  // Nhận cổng tra user, hai repository, phát sự kiện, đồng hồ và template mở transaction mỗi lần
  public SendFriendRequestUseCase(
      UserDirectory userDirectory,
      FriendRequestRepository requests,
      FriendshipRepository friendships,
      DomainEventPublisher eventPublisher,
      Clock clock,
      TransactionTemplate tx) {
    this.userDirectory = userDirectory;
    this.requests = requests;
    this.friendships = friendships;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
    this.tx = tx;
  }

  // Kiểm user đích ngoài transaction rồi thử tối đa 2 lần, mỗi lần một transaction mới
  public FriendRequestView execute(UserId requester, UserId target) {
    UserPair pair = UserPair.of(requester, target);
    if (!userDirectory.exists(target)) {
      throw new UserNotFoundException("user not found");
    }
    try {
      return tx.execute(status -> attempt(requester, target, pair));
    } catch (FriendRequestExistsException raced) {
      return tx.execute(status -> attempt(requester, target, pair));
    }
  }

  // Một lần thử: đã là bạn hoặc cùng chiều thì lỗi, ngược chiều thì chấp nhận, không thì gửi mới
  private FriendRequestView attempt(UserId requester, UserId target, UserPair pair) {
    if (friendships.exists(pair)) {
      throw new AlreadyFriendsException("already friends");
    }
    Instant now = clock.instant();
    Optional<FriendRequest> pending = requests.findPending(pair);
    FriendRequest request;
    if (pending.isPresent()) {
      request = pending.get();
      if (!request.requester().equals(target)) {
        throw new FriendRequestExistsException("a pending friend request already exists");
      }
      request.accept(requester, now);
    } else {
      request = FriendRequest.send(FriendRequestId.newId(), requester, target, now);
    }
    boolean accepted = pending.isPresent();
    var events = request.pullEvents();
    FriendRequest saved = requests.save(request);
    if (accepted) {
      friendships.insertIfAbsent(pair, now);
    }
    events.forEach(eventPublisher::publish);
    return FriendRequestView.from(saved);
  }
}

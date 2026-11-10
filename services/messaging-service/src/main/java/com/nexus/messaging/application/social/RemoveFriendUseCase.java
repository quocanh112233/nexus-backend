package com.nexus.messaging.application.social;

import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.FriendNotFoundException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRemoved;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa một người bạn
@Service
public class RemoveFriendUseCase {

  private final FriendshipRepository friendships;
  private final DomainEventPublisher eventPublisher;

  // Nhận repository bạn bè và cổng phát sự kiện qua constructor
  public RemoveFriendUseCase(
      FriendshipRepository friendships, DomainEventPublisher eventPublisher) {
    this.friendships = friendships;
    this.eventPublisher = eventPublisher;
  }

  // Xóa quan hệ rồi phát FriendRemoved, không phải bạn thì 404
  @Transactional
  public void execute(UserId me, UserId friend) {
    if (!friendships.delete(UserPair.of(me, friend))) {
      throw new FriendNotFoundException("not friends");
    }
    eventPublisher.publish(new FriendRemoved(me, friend));
  }
}

package com.nexus.notification.application.readstate;

import com.nexus.notification.application.port.DomainEventPublisher;
import com.nexus.notification.application.shared.ChannelNotFoundException;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.readstate.ReadStateSnapshot;
import com.nexus.notification.domain.readstate.ReadStateUpdated;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đánh dấu đã đọc tới một tin, mốc chỉ tiến, phát ReadStateUpdated cùng transaction
@Service
public class MarkChannelReadUseCase {

  private static final String NOT_FOUND = "Channel not found";

  private final ChannelActivityRepository activity;
  private final MembershipRepository members;
  private final ReadStateRepository readStates;
  private final DomainEventPublisher publisher;

  // Nhận ba repository projection và publisher outbox qua constructor
  public MarkChannelReadUseCase(
      ChannelActivityRepository activity,
      MembershipRepository members,
      ReadStateRepository readStates,
      DomainEventPublisher publisher) {
    this.activity = activity;
    this.members = members;
    this.readStates = readStates;
    this.publisher = publisher;
  }

  // Kênh lạ và user không có quyền cùng ném một lỗi để không lộ sự tồn tại của kênh
  @Transactional
  public ReadStateSnapshot execute(UserId user, ChannelId channel, MessageId message) {
    ChannelScope scope =
        activity
            .findLiveChannel(channel)
            .filter(s -> members.canAccess(user, s))
            .orElseThrow(() -> new ChannelNotFoundException(NOT_FOUND));
    ReadStateSnapshot snapshot = readStates.advance(user, scope.id(), message);
    publisher.publish(
        new ReadStateUpdated(user, scope.id(), snapshot.lastRead(), snapshot.mentionCount()));
    return snapshot;
  }
}

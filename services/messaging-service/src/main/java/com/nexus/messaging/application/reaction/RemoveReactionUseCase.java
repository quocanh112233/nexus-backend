package com.nexus.messaging.application.reaction;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.ReactionRemoved;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case gỡ emoji của chính user khỏi tin, chỉ cần VIEW_CHANNEL, gỡ khi không có là no-op
@Service
public class RemoveReactionUseCase {

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final ReactionRepository reactionRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, hai cổng lưu trữ và cổng phát sự kiện qua constructor
  public RemoveReactionUseCase(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      ReactionRepository reactionRepository,
      DomainEventPublisher eventPublisher) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.reactionRepository = reactionRepository;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm quyền xem kênh, nạp tin chưa xóa của kênh, xóa reaction của user và phát ReactionRemoved
  @Transactional
  public void execute(ChannelId channelId, MessageId messageId, UserId user, Emoji emoji) {
    ChannelAccess access = checker.check(user, channelId);
    access.require(ChannelPermission.VIEW_CHANNEL);
    messageRepository
        .findById(messageId)
        .filter(m -> m.channelId().equals(channelId) && m.deletedAt() == null)
        .orElseThrow(() -> new MessageNotFoundException("message not found"));
    if (reactionRepository.delete(messageId, user, emoji)) {
      eventPublisher.publish(new ReactionRemoved(messageId, channelId, user, emoji));
    }
  }
}

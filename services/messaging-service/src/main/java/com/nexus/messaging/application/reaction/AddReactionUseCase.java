package com.nexus.messaging.application.reaction;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.Reaction;
import com.nexus.messaging.domain.reaction.ReactionAdded;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case thả emoji lên tin, cần ADD_REACTIONS, thêm trùng là no-op và không phát lại sự kiện
@Service
public class AddReactionUseCase {

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final ReactionRepository reactionRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, hai cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public AddReactionUseCase(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      ReactionRepository reactionRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.reactionRepository = reactionRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm quyền xem kênh, nạp tin chưa xóa của kênh, kiểm ADD_REACTIONS, chèn và phát ReactionAdded
  @Transactional
  public void execute(ChannelId channelId, MessageId messageId, UserId user, Emoji emoji) {
    ChannelAccess access = checker.check(user, channelId);
    access.require(ChannelPermission.VIEW_CHANNEL);
    messageRepository
        .findById(messageId)
        .filter(m -> m.channelId().equals(channelId) && m.deletedAt() == null)
        .orElseThrow(() -> new MessageNotFoundException("message not found"));
    access.require(ChannelPermission.ADD_REACTIONS);
    Reaction reaction =
        new Reaction(messageId, user, emoji, clock.instant().truncatedTo(ChronoUnit.MICROS));
    if (reactionRepository.insertIfAbsent(reaction)) {
      eventPublisher.publish(new ReactionAdded(messageId, channelId, user, emoji));
    }
  }
}

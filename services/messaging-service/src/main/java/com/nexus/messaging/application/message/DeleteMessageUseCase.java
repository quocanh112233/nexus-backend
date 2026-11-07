package com.nexus.messaging.application.message;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa mềm tin, tác giả hoặc người có MANAGE_MESSAGES được xóa, xóa lần hai là no-op
@Service
public class DeleteMessageUseCase {

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public DeleteMessageUseCase(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm quyền xem, nạp tin thuộc kênh, kiểm tác giả hoặc MANAGE_MESSAGES, xóa mềm, phát event
  @Transactional
  public void execute(ChannelId channelId, MessageId messageId, UserId deleter) {
    ChannelAccess access = checker.check(deleter, channelId);
    access.require(ChannelPermission.VIEW_CHANNEL);
    Message message =
        messageRepository
            .findById(messageId)
            .filter(m -> m.channelId().equals(channelId))
            .orElseThrow(() -> new MessageNotFoundException("message not found"));
    if (!message.authorId().equals(deleter) && !access.has(ChannelPermission.MANAGE_MESSAGES)) {
      throw new PermissionDeniedException("missing permission MANAGE_MESSAGES");
    }
    message.delete(deleter, access.serverId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<DomainEvent> events = message.pullEvents();
    if (events.isEmpty()) {
      return;
    }
    messageRepository.save(message);
    events.forEach(eventPublisher::publish);
  }
}

package com.nexus.messaging.application.message;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.MentionParser;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageContent;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case sửa tin trong kênh, chỉ tác giả được sửa và tin phải thuộc kênh trên URL
@Service
public class EditMessageUseCase {

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public EditMessageUseCase(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm quyền xem (DM thêm quyền gửi), nạp tin, kiểm tác giả, sửa rồi lưu và phát MessageEdited
  @Transactional
  public MessageView execute(
      ChannelId channelId, MessageId messageId, UserId editor, String content) {
    ChannelAccess access = checker.check(editor, channelId);
    access.require(ChannelPermission.VIEW_CHANNEL);
    if (access.isDm()) {
      access.require(ChannelPermission.SEND_MESSAGES);
    }
    Message message =
        messageRepository
            .findById(messageId)
            .filter(m -> m.channelId().equals(channelId) && m.deletedAt() == null)
            .orElseThrow(() -> new MessageNotFoundException("message not found"));
    if (!message.authorId().equals(editor)) {
      throw new PermissionDeniedException("only the author can edit a message");
    }
    message.edit(
        new MessageContent(content),
        MentionParser.parse(content),
        access.serverId(),
        clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<DomainEvent> events = message.pullEvents();
    if (events.isEmpty()) {
      return MessageView.from(message);
    }
    Message saved = messageRepository.save(message);
    events.forEach(eventPublisher::publish);
    return MessageView.from(saved);
  }
}

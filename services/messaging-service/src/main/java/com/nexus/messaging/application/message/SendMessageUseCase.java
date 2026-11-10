package com.nexus.messaging.application.message;

import com.nexus.messaging.application.attachment.AttachmentInput;
import com.nexus.messaging.application.port.AttachmentVerifier;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.port.UploadSigner;
import com.nexus.messaging.application.port.VerifiedMedia;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.message.Attachment;
import com.nexus.messaging.domain.message.MentionParser;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageContent;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case gửi tin vào kênh server hoặc DM, idempotent theo nonce của tác giả
@Service
public class SendMessageUseCase {

  private static final int MAX_ATTACHMENTS = 5;

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final ChannelRefRepository channelRefRepository;
  private final UploadSigner uploadSigner;
  private final AttachmentVerifier attachmentVerifier;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, lưu trữ, thư mục upload, xác minh tệp, phát sự kiện và đồng hồ
  public SendMessageUseCase(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      ChannelRefRepository channelRefRepository,
      UploadSigner uploadSigner,
      AttachmentVerifier attachmentVerifier,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.channelRefRepository = channelRefRepository;
    this.uploadSigner = uploadSigner;
    this.attachmentVerifier = attachmentVerifier;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kết quả gửi: created=false khi nonce trùng và trả lại tin cũ
  public record SendResult(MessageView message, boolean created) {}

  // Kiểm quyền, tra nonce, đính kèm, mention, reply, ghi ref kênh server, lưu tin, phát event
  @Transactional
  public SendResult execute(SendMessageCommand cmd) {
    ChannelAccess access = checker.check(cmd.authorId(), cmd.channelId());
    access.require(ChannelPermission.SEND_MESSAGES);
    if (cmd.nonce() != null) {
      Optional<Message> existing =
          messageRepository.findByAuthorAndNonce(cmd.authorId(), cmd.nonce());
      if (existing.isPresent()) {
        if (!existing.get().channelId().equals(cmd.channelId())) {
          throw new DomainValidationException("nonce already used in another channel");
        }
        return new SendResult(MessageView.from(existing.get()), false);
      }
    }
    List<Attachment> attachments = verifiedAttachments(cmd, access);
    String text = cmd.content() == null ? "" : cmd.content();
    MessageContent content = new MessageContent(text);
    Set<UserId> mentions = MentionParser.parse(text);
    requireReplyInSameChannel(cmd);
    if (!access.isDm()) {
      channelRefRepository.upsertServerChannel(cmd.channelId(), access.serverId());
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Message message =
        Message.create(
            MessageId.newId(),
            cmd.channelId(),
            access.serverId(),
            cmd.authorId(),
            content,
            cmd.replyToId(),
            cmd.nonce(),
            mentions,
            attachments,
            now);
    Message saved = messageRepository.save(message);
    message.pullEvents().forEach(eventPublisher::publish);
    return new SendResult(MessageView.from(saved), true);
  }

  // Cần ATTACH_FILES, publicId đúng thư mục kênh và tác giả, tối đa 5, rồi lấy metadata thật
  private List<Attachment> verifiedAttachments(SendMessageCommand cmd, ChannelAccess access) {
    if (cmd.attachments().isEmpty()) {
      return List.of();
    }
    access.require(ChannelPermission.ATTACH_FILES);
    String prefix =
        AttachmentInput.folder(uploadSigner.folderRoot(), cmd.channelId(), cmd.authorId()) + "/";
    if (cmd.attachments().stream().anyMatch(a -> !AttachmentInput.isUnder(a.publicId(), prefix))) {
      throw new DomainValidationException("attachment publicId must be under " + prefix);
    }
    if (cmd.attachments().size() > MAX_ATTACHMENTS) {
      throw new DomainValidationException("at most 5 attachments are allowed");
    }
    return cmd.attachments().stream()
        .map(
            a -> {
              VerifiedMedia media = attachmentVerifier.verify(a.publicId(), a.resourceType());
              String name =
                  a.originalFilename() != null
                      ? a.originalFilename()
                      : a.publicId().substring(a.publicId().lastIndexOf('/') + 1);
              return new Attachment(
                  UUID.randomUUID(),
                  a.publicId(),
                  media.url(),
                  media.contentType(),
                  media.bytes(),
                  name);
            })
        .toList();
  }

  // Tin được trả lời phải tồn tại trong cùng kênh, tin đã xóa mềm vẫn hợp lệ
  private void requireReplyInSameChannel(SendMessageCommand cmd) {
    if (cmd.replyToId() == null) {
      return;
    }
    boolean ok =
        messageRepository
            .findById(cmd.replyToId())
            .filter(m -> m.channelId().equals(cmd.channelId()))
            .isPresent();
    if (!ok) {
      throw new DomainValidationException("replyToId must reference a message in the same channel");
    }
  }
}

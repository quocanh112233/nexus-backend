package com.nexus.messaging.infrastructure.persistence.message;

import com.nexus.messaging.domain.message.Attachment;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate Message và MessageJpaEntity
@Component
final class MessageMapper {

  // Dựng lại aggregate từ entity qua Message.rehydrate, mention theo thứ tự đọc từ DB
  Message toDomain(MessageJpaEntity e) {
    return toDomain(e, e.mentions);
  }

  // Dựng lại aggregate với mention theo thứ tự cho trước, vì bảng mention không lưu thứ tự
  Message toDomain(MessageJpaEntity e, Collection<UUID> mentions) {
    return Message.rehydrate(
        new MessageId(e.id),
        new ChannelId(e.channelId),
        new UserId(e.authorId),
        e.content,
        e.replyToId == null ? null : new MessageId(e.replyToId),
        e.nonce == null ? null : new Nonce(e.nonce),
        mentions.stream().map(UserId::new).collect(Collectors.toCollection(LinkedHashSet::new)),
        e.attachments.stream()
            .map(
                a ->
                    new Attachment(
                        a.id, a.publicId, a.url, a.contentType, a.bytes, a.originalFilename))
            .toList(),
        e.createdAt,
        e.editedAt,
        e.deletedAt,
        e.version);
  }

  // Dựng entity từ aggregate
  MessageJpaEntity toJpa(Message m) {
    MessageJpaEntity e = new MessageJpaEntity();
    e.id = m.id().value();
    e.channelId = m.channelId().value();
    e.authorId = m.authorId().value();
    e.content = m.content();
    e.replyToId = m.replyToId() == null ? null : m.replyToId().value();
    e.nonce = m.nonce() == null ? null : m.nonce().value();
    e.createdAt = m.createdAt();
    e.editedAt = m.editedAt();
    e.deletedAt = m.deletedAt();
    e.version = m.version();
    m.mentions().forEach(u -> e.mentions.add(u.value()));
    m.attachments().forEach(a -> e.attachments.add(toJpa(a)));
    return e;
  }

  // Dựng entity tệp đính kèm
  private AttachmentJpaEntity toJpa(Attachment a) {
    AttachmentJpaEntity e = new AttachmentJpaEntity();
    e.id = a.id();
    e.publicId = a.publicId();
    e.url = a.url();
    e.contentType = a.contentType();
    e.bytes = a.bytes();
    e.originalFilename = a.originalFilename();
    return e;
  }
}

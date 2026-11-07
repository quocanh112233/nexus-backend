package com.nexus.messaging.application.message;

import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.reaction.ReactionCount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Góc nhìn tin nhắn cho tầng giao tiếp; replyToId và editedAt có thể null, tin đã xóa là tombstone
public record MessageView(
    UUID id,
    UUID channelId,
    UUID authorId,
    String content,
    UUID replyToId,
    List<UUID> mentionUserIds,
    List<AttachmentView> attachments,
    List<ReactionCount> reactions,
    Instant createdAt,
    Instant editedAt,
    boolean deleted,
    long version) {

  // Tệp đính kèm trong góc nhìn tin
  public record AttachmentView(
      UUID id,
      String publicId,
      String url,
      String contentType,
      long bytes,
      String originalFilename) {}

  // Dựng góc nhìn từ aggregate, reactions rỗng; tin đã xóa không lộ nội dung, mention, attachment
  public static MessageView from(Message m) {
    boolean deleted = m.deletedAt() != null;
    return new MessageView(
        m.id().value(),
        m.channelId().value(),
        m.authorId().value(),
        deleted ? null : m.content(),
        m.replyToId() == null ? null : m.replyToId().value(),
        deleted ? List.of() : m.mentions().stream().map(u -> u.value()).toList(),
        deleted
            ? List.<AttachmentView>of()
            : m.attachments().stream()
                .map(
                    a ->
                        new AttachmentView(
                            a.id(),
                            a.publicId(),
                            a.url(),
                            a.contentType(),
                            a.bytes(),
                            a.originalFilename()))
                .toList(),
        List.of(),
        m.createdAt(),
        m.editedAt(),
        deleted,
        m.version());
  }

  // Bản sao có tóm tắt reaction
  public MessageView withReactions(List<ReactionCount> summary) {
    return new MessageView(
        id,
        channelId,
        authorId,
        content,
        replyToId,
        mentionUserIds,
        attachments,
        summary,
        createdAt,
        editedAt,
        deleted,
        version);
  }
}

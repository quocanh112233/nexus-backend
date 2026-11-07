package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.message.MessageView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phản hồi một tin nhắn; content null và mảng rỗng khi tin đã xóa
record MessageResponse(
    UUID id,
    UUID channelId,
    UUID authorId,
    String content,
    UUID replyToId,
    List<UUID> mentionUserIds,
    List<AttachmentResponse> attachments,
    List<ReactionResponse> reactions,
    Instant createdAt,
    Instant editedAt,
    boolean deleted,
    long version) {

  // Tệp đính kèm trong phản hồi
  record AttachmentResponse(
      UUID id,
      String publicId,
      String url,
      String contentType,
      long bytes,
      String originalFilename) {}

  // Tóm tắt một emoji trên tin: số lượt và người xem đã thả hay chưa
  record ReactionResponse(String emoji, int count, boolean me) {}

  // Dựng phản hồi từ góc nhìn tin
  static MessageResponse from(MessageView v) {
    return new MessageResponse(
        v.id(),
        v.channelId(),
        v.authorId(),
        v.content(),
        v.replyToId(),
        v.mentionUserIds(),
        v.attachments().stream()
            .map(
                a ->
                    new AttachmentResponse(
                        a.id(),
                        a.publicId(),
                        a.url(),
                        a.contentType(),
                        a.bytes(),
                        a.originalFilename()))
            .toList(),
        v.reactions().stream()
            .map(r -> new ReactionResponse(r.emoji(), r.count(), r.me()))
            .toList(),
        v.createdAt(),
        v.editedAt(),
        v.deleted(),
        v.version());
  }
}

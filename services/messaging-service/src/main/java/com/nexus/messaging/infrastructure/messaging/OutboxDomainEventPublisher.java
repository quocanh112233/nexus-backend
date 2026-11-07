package com.nexus.messaging.infrastructure.messaging;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.domain.message.MessageCreated;
import com.nexus.messaging.domain.message.MessageDeleted;
import com.nexus.messaging.domain.message.MessageEdited;
import com.nexus.messaging.domain.reaction.ReactionAdded;
import com.nexus.messaging.domain.reaction.ReactionRemoved;
import com.nexus.messaging.domain.shared.DomainEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Phát sự kiện miền bằng cách ghi vào outbox cùng transaction của use case
@Component
class OutboxDomainEventPublisher implements DomainEventPublisher {

  private final OutboxWriter outboxWriter;

  // Nhận OutboxWriter qua constructor
  OutboxDomainEventPublisher(OutboxWriter outboxWriter) {
    this.outboxWriter = outboxWriter;
  }

  // Đổi sự kiện miền thành OutboxMessage theo event-catalog rồi ghi, event chưa map thì ném lỗi
  @Override
  public void publish(DomainEvent event) {
    outboxWriter.append(
        switch (event) {
          case MessageCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", e.messageId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put("serverId", e.serverId().toString());
            payload.put("authorId", e.authorId().value().toString());
            payload.put("content", e.content());
            payload.put(
                "replyToId", e.replyToId() == null ? null : e.replyToId().value().toString());
            payload.put(
                "attachments",
                e.attachments().stream()
                    .map(
                        a -> {
                          Map<String, Object> file = new LinkedHashMap<>();
                          file.put("publicId", a.publicId());
                          file.put("url", a.url());
                          file.put("contentType", a.contentType());
                          file.put("bytes", a.bytes());
                          return file;
                        })
                    .toList());
            payload.put(
                "mentionUserIds", e.mentions().stream().map(u -> u.value().toString()).toList());
            payload.put("createdAt", e.createdAt().toString());
            payload.put("nonce", e.nonce() == null ? null : e.nonce().value());
            yield new OutboxMessage(
                "Message", e.messageId().value(), "MessageCreated", 1, "message.created", payload);
          }
          case MessageEdited e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", e.messageId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put("serverId", e.serverId().toString());
            payload.put("content", e.content());
            payload.put(
                "mentionUserIds", e.mentions().stream().map(u -> u.value().toString()).toList());
            payload.put("editedAt", e.editedAt().toString());
            payload.put("version", e.version());
            yield new OutboxMessage(
                "Message", e.messageId().value(), "MessageEdited", 1, "message.edited", payload);
          }
          case MessageDeleted e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", e.messageId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put("serverId", e.serverId().toString());
            payload.put("deletedBy", e.deletedBy().value().toString());
            yield new OutboxMessage(
                "Message", e.messageId().value(), "MessageDeleted", 1, "message.deleted", payload);
          }
          case ReactionAdded e ->
              new OutboxMessage(
                  "Reaction",
                  e.messageId().value(),
                  "ReactionAdded",
                  1,
                  "reaction.added",
                  reactionPayload(
                      e.messageId().value(),
                      e.channelId().value(),
                      e.userId().value(),
                      e.emoji().value()));
          case ReactionRemoved e ->
              new OutboxMessage(
                  "Reaction",
                  e.messageId().value(),
                  "ReactionRemoved",
                  1,
                  "reaction.removed",
                  reactionPayload(
                      e.messageId().value(),
                      e.channelId().value(),
                      e.userId().value(),
                      e.emoji().value()));
          default -> throw new IllegalArgumentException("unmapped event " + event.getClass());
        });
  }

  // Payload chung của reaction.added và reaction.removed theo event-catalog
  private static Map<String, Object> reactionPayload(
      UUID messageId, UUID channelId, UUID userId, String emoji) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messageId", messageId.toString());
    payload.put("channelId", channelId.toString());
    payload.put("userId", userId.toString());
    payload.put("emoji", emoji);
    return payload;
  }
}

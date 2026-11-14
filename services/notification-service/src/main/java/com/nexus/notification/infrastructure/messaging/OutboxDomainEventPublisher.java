package com.nexus.notification.infrastructure.messaging;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.notification.application.port.DomainEventPublisher;
import com.nexus.notification.domain.readstate.MentionCreated;
import com.nexus.notification.domain.readstate.ReadStateUpdated;
import com.nexus.notification.domain.shared.DomainEvent;
import java.util.LinkedHashMap;
import java.util.Map;
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
          case ReadStateUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", e.userId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put("lastReadMessageId", e.lastReadMessageId().value().toString());
            payload.put("mentionCount", e.mentionCount());
            yield new OutboxMessage(
                "ReadState",
                e.userId().value(),
                "ReadStateUpdated",
                1,
                "read-state.updated",
                payload);
          }
          case MentionCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", e.userId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put("serverId", e.serverId() == null ? null : e.serverId().value().toString());
            payload.put("messageId", e.messageId().value().toString());
            payload.put("authorId", e.authorId().value().toString());
            payload.put("mentionCount", e.mentionCount());
            yield new OutboxMessage(
                "Mention", e.messageId().value(), "MentionCreated", 1, "mention.created", payload);
          }
          default -> throw new IllegalArgumentException("unmapped event " + event.getClass());
        });
  }
}

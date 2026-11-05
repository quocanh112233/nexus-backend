package com.nexus.identity.infrastructure.messaging;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.domain.shared.DomainEvent;
import com.nexus.identity.domain.user.UserProfileUpdated;
import com.nexus.identity.domain.user.UserRegistered;
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

  // Đổi sự kiện miền thành OutboxMessage theo event-catalog rồi ghi
  @Override
  public void publish(DomainEvent event) {
    outboxWriter.append(
        switch (event) {
          case UserRegistered e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", e.userId().value().toString());
            payload.put("username", e.username().value());
            payload.put("displayName", e.displayName().value());
            yield new OutboxMessage(
                "User", e.userId().value(), "UserRegistered", 1, "user.registered", payload);
          }
          case UserProfileUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", e.userId().value().toString());
            payload.put("displayName", e.displayName().value());
            payload.put("avatarUrl", e.avatarUrl());
            payload.put("aboutMe", e.aboutMe().value());
            yield new OutboxMessage(
                "User",
                e.userId().value(),
                "UserProfileUpdated",
                1,
                "user.profile-updated",
                payload);
          }
          default -> throw new IllegalArgumentException("unmapped event " + event.getClass());
        });
  }
}

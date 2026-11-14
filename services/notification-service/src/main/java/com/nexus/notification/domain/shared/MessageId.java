package com.nexus.notification.domain.shared;

import java.util.UUID;

// Tham chiếu tới tin nhắn của messaging-service, bọc UUID
public record MessageId(UUID value) {

  // Từ chối giá trị rỗng
  public MessageId {
    if (value == null) {
      throw new DomainValidationException("messageId is required");
    }
  }
}

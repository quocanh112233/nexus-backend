package com.nexus.notification.domain.shared;

import java.util.UUID;

// Tham chiếu tới server của community-service, bọc UUID
public record ServerId(UUID value) {

  // Từ chối giá trị rỗng
  public ServerId {
    if (value == null) {
      throw new DomainValidationException("serverId is required");
    }
  }
}

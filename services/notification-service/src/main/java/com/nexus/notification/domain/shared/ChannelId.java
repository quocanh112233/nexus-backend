package com.nexus.notification.domain.shared;

import java.util.UUID;

// Tham chiếu tới kênh của community-service hoặc DM, bọc UUID
public record ChannelId(UUID value) {

  // Từ chối giá trị rỗng
  public ChannelId {
    if (value == null) {
      throw new DomainValidationException("channelId is required");
    }
  }
}

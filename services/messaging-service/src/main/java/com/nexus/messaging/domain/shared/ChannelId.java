package com.nexus.messaging.domain.shared;

import java.util.UUID;

// Tham chiếu tới kênh của community-service, bọc UUID
public record ChannelId(UUID value) {

  // Từ chối giá trị rỗng
  public ChannelId {
    if (value == null) {
      throw new DomainValidationException("channelId is required");
    }
  }

  // Phân tích chuỗi UUID, sai định dạng thì ném lỗi miền
  public static ChannelId of(String text) {
    if (text == null) {
      throw new DomainValidationException("channelId is required");
    }
    try {
      return new ChannelId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("channelId is not a valid UUID");
    }
  }
}

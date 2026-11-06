package com.nexus.community.domain.channel;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.community.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh kênh dạng UUIDv7
public record ChannelId(UUID value) {

  // Từ chối giá trị rỗng
  public ChannelId {
    if (value == null) {
      throw new DomainValidationException("channelId is required");
    }
  }

  // Sinh định danh mới theo thời gian
  public static ChannelId newId() {
    return new ChannelId(UuidCreator.getTimeOrderedEpoch());
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

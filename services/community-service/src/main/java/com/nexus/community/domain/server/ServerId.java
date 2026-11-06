package com.nexus.community.domain.server;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.community.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh server dạng UUIDv7
public record ServerId(UUID value) {

  // Từ chối giá trị rỗng
  public ServerId {
    if (value == null) {
      throw new DomainValidationException("serverId is required");
    }
  }

  // Sinh định danh mới theo thời gian
  public static ServerId newId() {
    return new ServerId(UuidCreator.getTimeOrderedEpoch());
  }

  // Phân tích chuỗi UUID, sai định dạng thì ném lỗi miền
  public static ServerId of(String text) {
    if (text == null) {
      throw new DomainValidationException("serverId is required");
    }
    try {
      return new ServerId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("serverId is not a valid UUID");
    }
  }
}

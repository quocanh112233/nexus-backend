package com.nexus.notification.domain.shared;

import java.util.UUID;

// Tham chiếu tới người dùng của identity-service, bọc UUID
public record UserId(UUID value) {

  // Từ chối giá trị rỗng
  public UserId {
    if (value == null) {
      throw new DomainValidationException("userId is required");
    }
  }

  // Phân tích chuỗi UUID, sai định dạng thì ném lỗi miền
  public static UserId of(String text) {
    if (text == null) {
      throw new DomainValidationException("userId is required");
    }
    try {
      return new UserId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("userId is not a valid UUID");
    }
  }
}

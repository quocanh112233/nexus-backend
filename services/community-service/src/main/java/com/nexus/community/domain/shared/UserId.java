package com.nexus.community.domain.shared;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

// Tham chiếu tới người dùng của identity-service, dạng UUIDv7
public record UserId(UUID value) {

  // Từ chối giá trị rỗng
  public UserId {
    if (value == null) {
      throw new DomainValidationException("userId is required");
    }
  }

  // Sinh định danh mới theo thời gian
  public static UserId newId() {
    return new UserId(UuidCreator.getTimeOrderedEpoch());
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

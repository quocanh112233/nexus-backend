package com.nexus.community.domain.category;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.community.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh category dạng UUIDv7
public record CategoryId(UUID value) {

  // Từ chối giá trị rỗng
  public CategoryId {
    if (value == null) {
      throw new DomainValidationException("categoryId is required");
    }
  }

  // Sinh định danh mới theo thời gian
  public static CategoryId newId() {
    return new CategoryId(UuidCreator.getTimeOrderedEpoch());
  }

  // Phân tích chuỗi UUID, sai định dạng thì ném lỗi miền
  public static CategoryId of(String text) {
    if (text == null) {
      throw new DomainValidationException("categoryId is required");
    }
    try {
      return new CategoryId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("categoryId is not a valid UUID");
    }
  }
}

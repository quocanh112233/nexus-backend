package com.nexus.community.domain.server;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.community.domain.shared.DomainValidationException;
import java.util.UUID;

// Định danh role dạng UUIDv7
public record RoleId(UUID value) {

  // Từ chối giá trị rỗng
  public RoleId {
    if (value == null) {
      throw new DomainValidationException("roleId is required");
    }
  }

  // Sinh định danh mới theo thời gian
  public static RoleId newId() {
    return new RoleId(UuidCreator.getTimeOrderedEpoch());
  }

  // Phân tích chuỗi UUID, sai định dạng thì ném lỗi miền
  public static RoleId of(String text) {
    if (text == null) {
      throw new DomainValidationException("roleId is required");
    }
    try {
      return new RoleId(UUID.fromString(text));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("roleId is not a valid UUID");
    }
  }
}

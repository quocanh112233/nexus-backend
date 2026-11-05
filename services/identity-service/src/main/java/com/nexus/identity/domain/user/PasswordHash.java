package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;

// Chuỗi băm mật khẩu không rỗng
public record PasswordHash(String value) {

  // Từ chối giá trị rỗng
  public PasswordHash {
    if (value == null || value.isBlank()) {
      throw new DomainValidationException("passwordHash is required");
    }
  }
}

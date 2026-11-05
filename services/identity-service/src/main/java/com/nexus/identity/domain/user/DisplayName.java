package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;

// Tên hiển thị dài 1 đến 32 ký tự
public record DisplayName(String value) {

  // Cắt khoảng trắng rồi kiểm tra độ dài
  public DisplayName {
    if (value == null) {
      throw new DomainValidationException("displayName is required");
    }
    value = value.trim();
    if (value.isEmpty() || value.length() > 32) {
      throw new DomainValidationException("displayName must be 1-32 chars");
    }
  }
}

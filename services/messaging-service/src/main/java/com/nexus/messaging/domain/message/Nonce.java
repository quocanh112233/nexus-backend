package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.DomainValidationException;

// Khóa idempotency do client chọn, 1 đến 64 ký tự
public record Nonce(String value) {

  private static final int MAX_LENGTH = 64;

  // Từ chối nonce rỗng, quá dài hoặc chứa NUL
  public Nonce {
    if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
      throw new DomainValidationException("nonce must be 1 to 64 characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new DomainValidationException("nonce must not contain NUL");
    }
  }
}

package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.nio.charset.StandardCharsets;

// Mật khẩu thô từ 8 ký tự và tối đa 72 byte UTF-8, không lộ khi in
public record RawPassword(String value) {

  // Kiểm tra số ký tự và số byte UTF-8
  public RawPassword {
    if (value == null || value.length() < 8) {
      throw new DomainValidationException("password must be at least 8 chars");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new DomainValidationException("password must be at most 72 bytes");
    }
  }

  // Che giá trị để không lộ qua log
  @Override
  public String toString() {
    return "****";
  }
}

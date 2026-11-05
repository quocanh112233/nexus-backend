package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

// Email đã chuẩn hóa chữ thường, tối đa 254 ký tự
public record Email(String value) {

  private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  // Chuẩn hóa rồi kiểm tra định dạng và độ dài
  public Email {
    if (value == null) {
      throw new DomainValidationException("email is required");
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254 || !FORMAT.matcher(value).matches()) {
      throw new DomainValidationException("email is invalid");
    }
  }
}

package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

// Tên đăng nhập chữ thường gồm a-z, 0-9, dấu gạch dưới và dấu chấm
public record Username(String value) {

  private static final Pattern FORMAT = Pattern.compile("^[a-z0-9_.]{2,32}$");

  // Hạ chữ thường rồi kiểm tra định dạng
  public Username {
    if (value == null) {
      throw new DomainValidationException("username is required");
    }
    value = value.toLowerCase(Locale.ROOT);
    if (!FORMAT.matcher(value).matches()) {
      throw new DomainValidationException("username is invalid");
    }
  }
}

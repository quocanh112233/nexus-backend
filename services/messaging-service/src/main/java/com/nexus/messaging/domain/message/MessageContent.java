package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.DomainValidationException;

// Nội dung tin tối đa 2000 code point, không chứa NUL, rỗng được khi có đính kèm, giữ khoảng trắng
public record MessageContent(String value) {

  private static final int MAX_CODE_POINTS = 2000;

  // Đếm theo code point để emoji ngoài BMP chỉ tính một
  public MessageContent {
    if (value == null) {
      throw new DomainValidationException("content is required");
    }
    if (value.indexOf('\0') >= 0) {
      throw new DomainValidationException("content must not contain NUL");
    }
    if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
      throw new DomainValidationException("content must be at most 2000 characters");
    }
  }
}

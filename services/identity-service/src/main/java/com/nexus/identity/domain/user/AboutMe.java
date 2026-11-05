package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainValidationException;

// Giới thiệu bản thân tối đa 190 ký tự, null thành chuỗi rỗng
public record AboutMe(String value) {

  // Cắt khoảng trắng, null thành rỗng rồi kiểm tra độ dài
  public AboutMe {
    value = value == null ? "" : value.trim();
    if (value.length() > 190) {
      throw new DomainValidationException("aboutMe must be at most 190 chars");
    }
  }
}

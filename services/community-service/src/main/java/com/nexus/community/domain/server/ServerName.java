package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainValidationException;

// Tên server dài 1 đến 100 ký tự sau khi cắt khoảng trắng hai đầu
public record ServerName(String value) {

  // Cắt khoảng trắng rồi kiểm tra độ dài
  public ServerName {
    if (value == null) {
      throw new DomainValidationException("server name is required");
    }
    value = value.strip();
    if (value.isEmpty() || value.length() > 100) {
      throw new DomainValidationException("server name must be 1..100 characters");
    }
  }
}

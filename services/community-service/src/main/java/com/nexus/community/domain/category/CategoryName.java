package com.nexus.community.domain.category;

import com.nexus.community.domain.shared.DomainValidationException;

// Tên category dài 1 đến 100 ký tự sau khi cắt khoảng trắng hai đầu
public record CategoryName(String value) {

  // Cắt khoảng trắng rồi kiểm tra độ dài
  public CategoryName {
    if (value == null) {
      throw new DomainValidationException("category name is required");
    }
    value = value.strip();
    if (value.isEmpty() || value.length() > 100) {
      throw new DomainValidationException("category name must be 1..100 characters");
    }
  }
}

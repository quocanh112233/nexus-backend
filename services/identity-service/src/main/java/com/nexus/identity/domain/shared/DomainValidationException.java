package com.nexus.identity.domain.shared;

// Lỗi vi phạm bất biến của value object hoặc aggregate
public class DomainValidationException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả vi phạm
  public DomainValidationException(String message) {
    super(message);
  }
}

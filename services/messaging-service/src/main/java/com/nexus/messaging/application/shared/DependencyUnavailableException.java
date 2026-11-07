package com.nexus.messaging.application.shared;

// Service phụ thuộc không dùng được, caller phải fail-closed
public class DependencyUnavailableException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp và nguyên nhân gốc
  public DependencyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

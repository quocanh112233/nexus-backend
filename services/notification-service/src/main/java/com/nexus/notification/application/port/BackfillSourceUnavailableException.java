package com.nexus.notification.application.port;

// Nguồn snapshot membership không trả lời được, có thể thử lại
public class BackfillSourceUnavailableException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp và nguyên nhân gốc
  public BackfillSourceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

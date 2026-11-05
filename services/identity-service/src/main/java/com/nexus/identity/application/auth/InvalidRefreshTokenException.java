package com.nexus.identity.application.auth;

// Refresh token thiếu, lạ, hết hạn hoặc mồ côi, cố ý không phân biệt lý do
public class InvalidRefreshTokenException extends RuntimeException {

  // Tạo lỗi với thông điệp chung, không stack trace
  public InvalidRefreshTokenException() {
    super("Invalid refresh token", null, false, false);
  }
}

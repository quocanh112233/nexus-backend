package com.nexus.identity.application.auth;

// Refresh token đã thu hồi bị xuất trình lại, cả family đã bị thu hồi
public class RefreshTokenReusedException extends RuntimeException {

  // Tạo lỗi với thông điệp chung, không stack trace
  public RefreshTokenReusedException() {
    super("Refresh token reuse detected", null, false, false);
  }
}

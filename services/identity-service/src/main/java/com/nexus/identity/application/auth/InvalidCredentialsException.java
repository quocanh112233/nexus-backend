package com.nexus.identity.application.auth;

// Đăng nhập thất bại, cố ý không phân biệt email lạ hay sai mật khẩu
public class InvalidCredentialsException extends RuntimeException {

  // Tạo lỗi với thông điệp chung
  public InvalidCredentialsException() {
    super("Invalid credentials", null, false, false);
  }
}

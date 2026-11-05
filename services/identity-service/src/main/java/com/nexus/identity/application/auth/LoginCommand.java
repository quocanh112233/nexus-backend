package com.nexus.identity.application.auth;

// Lệnh đăng nhập bằng email và mật khẩu kèm user agent và ip của client
public record LoginCommand(String email, String password, String userAgent, String ip) {

  // Che mật khẩu để không lộ qua log
  @Override
  public String toString() {
    return "LoginCommand[email=" + email + ", userAgent=" + userAgent + ", ip=" + ip + "]";
  }
}

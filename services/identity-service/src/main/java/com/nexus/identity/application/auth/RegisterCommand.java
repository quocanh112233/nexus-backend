package com.nexus.identity.application.auth;

// Lệnh đăng ký, displayName null thì dùng username
public record RegisterCommand(String email, String username, String displayName, String password) {

  // Che mật khẩu để không lộ qua log
  @Override
  public String toString() {
    return "RegisterCommand[email=" + email + ", username=" + username + "]";
  }
}

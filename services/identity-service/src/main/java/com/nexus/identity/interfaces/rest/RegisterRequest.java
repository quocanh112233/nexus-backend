package com.nexus.identity.interfaces.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Body đăng ký, kiểm tra null và độ dài ở biên REST
public record RegisterRequest(
    @NotBlank @Size(max = 254) String email,
    @NotBlank @Size(min = 2, max = 32) String username,
    @Size(max = 32) String displayName,
    @NotBlank @Size(min = 8, max = 72) String password) {

  // Che mật khẩu để không lộ qua log
  @Override
  public String toString() {
    return "RegisterRequest[email=" + email + ", username=" + username + "]";
  }
}

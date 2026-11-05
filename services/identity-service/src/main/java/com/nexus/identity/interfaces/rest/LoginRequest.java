package com.nexus.identity.interfaces.rest;

import jakarta.validation.constraints.NotNull;

// Body đăng nhập, chỉ chặn null ở biên REST, sai định dạng xử lý như sai thông tin đăng nhập
public record LoginRequest(@NotNull String email, @NotNull String password) {

  // Che mật khẩu để không lộ qua log
  @Override
  public String toString() {
    return "LoginRequest[email=" + email + "]";
  }
}

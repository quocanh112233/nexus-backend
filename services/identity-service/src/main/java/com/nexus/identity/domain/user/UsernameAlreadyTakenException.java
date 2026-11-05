package com.nexus.identity.domain.user;

// Lỗi tên đăng nhập đã được dùng bởi người dùng khác
public class UsernameAlreadyTakenException extends RuntimeException {

  // Tạo lỗi cho tên đăng nhập bị trùng
  public UsernameAlreadyTakenException(Username username) {
    super("username already taken: " + username.value());
  }
}

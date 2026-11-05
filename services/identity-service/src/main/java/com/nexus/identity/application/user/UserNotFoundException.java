package com.nexus.identity.application.user;

// Không tìm thấy người dùng theo id hoặc tên đăng nhập
public class UserNotFoundException extends RuntimeException {

  // Thông điệp chung, không lộ khóa tra cứu
  public UserNotFoundException() {
    super("User not found");
  }
}

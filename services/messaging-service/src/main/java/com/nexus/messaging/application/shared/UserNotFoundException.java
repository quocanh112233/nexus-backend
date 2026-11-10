package com.nexus.messaging.application.shared;

// User đích không tồn tại ở Identity
public class UserNotFoundException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public UserNotFoundException(String message) {
    super(message);
  }
}

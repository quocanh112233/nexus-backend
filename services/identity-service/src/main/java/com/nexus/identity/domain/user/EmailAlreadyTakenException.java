package com.nexus.identity.domain.user;

// Lỗi email đã được dùng bởi người dùng khác
public class EmailAlreadyTakenException extends RuntimeException {

  // Tạo lỗi cho email bị trùng
  public EmailAlreadyTakenException(Email email) {
    super("email already taken: " + email.value());
  }
}

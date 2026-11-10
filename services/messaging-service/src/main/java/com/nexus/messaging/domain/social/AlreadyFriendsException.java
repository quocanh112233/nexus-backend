package com.nexus.messaging.domain.social;

// Lỗi hai người đã là bạn nên không gửi thêm lời mời
public class AlreadyFriendsException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả
  public AlreadyFriendsException(String message) {
    super(message);
  }
}

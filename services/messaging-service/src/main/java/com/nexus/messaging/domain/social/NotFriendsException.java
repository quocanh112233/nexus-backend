package com.nexus.messaging.domain.social;

// Lỗi hai người không còn là bạn nên không mở được DM
public class NotFriendsException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả
  public NotFriendsException(String message) {
    super(message);
  }
}

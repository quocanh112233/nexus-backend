package com.nexus.messaging.domain.social;

// Lỗi cặp người dùng đã có một lời mời kết bạn đang chờ
public class FriendRequestExistsException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả
  public FriendRequestExistsException(String message) {
    super(message);
  }
}

package com.nexus.messaging.domain.social;

// Lỗi lời mời kết bạn không còn ở trạng thái chờ
public class FriendRequestNotPendingException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả
  public FriendRequestNotPendingException(String message) {
    super(message);
  }
}

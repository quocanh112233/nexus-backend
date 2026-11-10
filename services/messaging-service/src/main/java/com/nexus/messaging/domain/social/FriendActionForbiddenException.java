package com.nexus.messaging.domain.social;

// Lỗi người thực hiện không có quyền với lời mời kết bạn
public class FriendActionForbiddenException extends RuntimeException {

  // Tạo lỗi với thông điệp mô tả
  public FriendActionForbiddenException(String message) {
    super(message);
  }
}

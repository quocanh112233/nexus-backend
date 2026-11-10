package com.nexus.messaging.application.shared;

// Lời mời hoặc quan hệ bạn bè không tồn tại, hoặc người gọi không liên quan tới nó
public class FriendNotFoundException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public FriendNotFoundException(String message) {
    super(message);
  }
}

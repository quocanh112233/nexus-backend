package com.nexus.messaging.application.shared;

// Tin nhắn không tồn tại hoặc không thuộc kênh được yêu cầu
public class MessageNotFoundException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public MessageNotFoundException(String message) {
    super(message);
  }
}

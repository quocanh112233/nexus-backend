package com.nexus.messaging.application.shared;

// Kênh không tồn tại hoặc user không nhìn thấy kênh
public class ChannelNotFoundException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public ChannelNotFoundException(String message) {
    super(message);
  }
}

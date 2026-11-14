package com.nexus.notification.application.shared;

// Kênh không tồn tại, đã xóa hoặc user không nhìn thấy kênh
public class ChannelNotFoundException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public ChannelNotFoundException(String message) {
    super(message);
  }
}

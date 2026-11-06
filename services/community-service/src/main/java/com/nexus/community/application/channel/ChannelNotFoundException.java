package com.nexus.community.application.channel;

// Kênh không tồn tại hoặc không thuộc server trong đường dẫn
public class ChannelNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public ChannelNotFoundException() {
    super("Channel not found");
  }
}

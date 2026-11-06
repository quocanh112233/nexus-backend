package com.nexus.community.application.channel;

// Overwrite cần gỡ không tồn tại
public class OverwriteNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public OverwriteNotFoundException() {
    super("Overwrite not found");
  }
}

package com.nexus.community.application.ban;

// User đích không đang bị ban ở server
public class BanNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public BanNotFoundException() {
    super("Ban not found");
  }
}

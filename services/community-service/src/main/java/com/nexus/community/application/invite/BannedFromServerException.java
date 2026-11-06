package com.nexus.community.application.invite;

// Lỗi user đang bị ban nên không được tham gia server
public class BannedFromServerException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public BannedFromServerException() {
    super("You are banned from this server");
  }
}

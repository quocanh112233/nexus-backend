package com.nexus.community.application.invite;

// Lời mời không có, sai định dạng hoặc không còn dùng được nên không xem hay thu hồi được
public class InviteNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public InviteNotFoundException() {
    super("Invite not found");
  }
}

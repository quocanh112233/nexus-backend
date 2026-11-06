package com.nexus.community.application.invite;

// Lời mời không dùng được để tham gia: không có, sai định dạng, hết hạn, thu hồi hoặc hết lượt
public class InviteInvalidException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định, không phân biệt lý do
  public InviteInvalidException() {
    super("Invite is invalid");
  }
}

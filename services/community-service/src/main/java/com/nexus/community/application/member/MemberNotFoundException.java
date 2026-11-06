package com.nexus.community.application.member;

// Người dùng đích không phải thành viên của server
public class MemberNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public MemberNotFoundException() {
    super("Member not found");
  }
}

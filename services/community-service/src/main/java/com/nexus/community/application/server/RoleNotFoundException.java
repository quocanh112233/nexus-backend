package com.nexus.community.application.server;

// Role không tồn tại hoặc không thuộc server trong đường dẫn
public class RoleNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public RoleNotFoundException() {
    super("Role not found");
  }
}

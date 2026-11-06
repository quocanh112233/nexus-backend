package com.nexus.community.application.server;

// Server không tồn tại hoặc người gọi không phải thành viên, hai trường hợp không phân biệt được
public class ServerNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định để không lộ việc server có tồn tại
  public ServerNotFoundException() {
    super("Server not found");
  }
}

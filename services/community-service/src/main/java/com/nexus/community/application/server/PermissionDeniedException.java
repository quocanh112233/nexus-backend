package com.nexus.community.application.server;

// Người gọi là thành viên nhưng không đủ quyền thực hiện thao tác
public class PermissionDeniedException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public PermissionDeniedException() {
    this("Permission denied");
  }

  // Tạo lỗi với thông điệp riêng cho thao tác bị cấm
  public PermissionDeniedException(String message) {
    super(message);
  }
}

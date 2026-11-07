package com.nexus.messaging.application.shared;

// User thấy kênh nhưng thiếu quyền cho thao tác
public class PermissionDeniedException extends RuntimeException {

  // Tạo ngoại lệ với thông điệp
  public PermissionDeniedException(String message) {
    super(message);
  }
}

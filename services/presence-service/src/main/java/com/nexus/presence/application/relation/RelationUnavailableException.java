package com.nexus.presence.application.relation;

// Không nạp được quan hệ của user vì Community không khả dụng
public class RelationUnavailableException extends RuntimeException {

  // Gói lỗi gốc kèm thông điệp
  public RelationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

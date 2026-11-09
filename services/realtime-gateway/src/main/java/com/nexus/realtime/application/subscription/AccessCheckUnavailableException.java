package com.nexus.realtime.application.subscription;

// Không hỏi được quyền vì hạ tầng lỗi, người gọi phải từ chối (fail-closed)
public class AccessCheckUnavailableException extends RuntimeException {

  // Gói lỗi gốc của lần gọi thất bại
  public AccessCheckUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

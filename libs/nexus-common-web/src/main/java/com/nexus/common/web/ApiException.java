package com.nexus.common.web;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ mang HTTP status và code ổn định, được đổi thành Problem Details
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  // Tạo lỗi nghiệp vụ với HTTP status, code ổn định và mô tả chi tiết
  public ApiException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  // Trả HTTP status của lỗi
  public HttpStatus status() {
    return status;
  }

  // Trả mã lỗi ổn định cho frontend
  public String code() {
    return code;
  }
}

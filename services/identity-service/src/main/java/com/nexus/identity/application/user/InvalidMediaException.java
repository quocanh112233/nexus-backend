package com.nexus.identity.application.user;

// publicId ảnh không thuộc thư mục avatar của người gọi hoặc sai định dạng
public class InvalidMediaException extends RuntimeException {

  // Thông điệp chung, không lộ cấu hình thư mục
  public InvalidMediaException() {
    super("Invalid avatar publicId");
  }
}

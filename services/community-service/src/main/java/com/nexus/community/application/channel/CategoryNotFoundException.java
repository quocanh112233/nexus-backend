package com.nexus.community.application.channel;

// Category không tồn tại hoặc không thuộc server trong đường dẫn
public class CategoryNotFoundException extends RuntimeException {

  // Tạo lỗi với thông điệp cố định
  public CategoryNotFoundException() {
    super("Category not found");
  }
}

package com.nexus.community.application.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.ServerId;

// Kiểm tra category thuộc server, dùng chung cho các use case kênh
final class CategoryChecks {

  // Lớp tiện ích không tạo thể hiện
  private CategoryChecks() {}

  // Ném CategoryNotFound nếu category không có hoặc thuộc server khác
  static void requireInServer(
      CategoryRepository repository, ServerId serverId, CategoryId categoryId) {
    repository
        .findById(categoryId)
        .filter(c -> c.serverId().equals(serverId))
        .orElseThrow(CategoryNotFoundException::new);
  }
}

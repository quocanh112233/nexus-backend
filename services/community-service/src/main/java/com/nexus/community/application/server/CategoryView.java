package com.nexus.community.application.server;

import com.nexus.community.domain.category.Category;
import java.util.UUID;

// Góc nhìn category trong chi tiết server
public record CategoryView(UUID id, String name, int position) {

  // Dựng góc nhìn từ aggregate category
  public static CategoryView from(Category c) {
    return new CategoryView(c.id().value(), c.name().value(), c.position().value());
  }
}

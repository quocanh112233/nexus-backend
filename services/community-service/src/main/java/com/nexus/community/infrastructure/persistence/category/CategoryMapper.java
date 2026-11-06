package com.nexus.community.infrastructure.persistence.category;

import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate Category và CategoryJpaEntity
@Component
final class CategoryMapper {

  // Dựng lại aggregate từ entity, bảng không có updated_at nên lấy created_at
  Category toDomain(CategoryJpaEntity e) {
    return Category.rehydrate(
        new CategoryId(e.id),
        new ServerId(e.serverId),
        new CategoryName(e.name),
        new Position(e.position),
        e.createdAt,
        e.createdAt);
  }

  // Dựng entity từ aggregate
  CategoryJpaEntity toJpa(Category c) {
    CategoryJpaEntity e = new CategoryJpaEntity();
    e.id = c.id().value();
    e.serverId = c.serverId().value();
    e.name = c.name().value();
    e.position = c.position().value();
    e.createdAt = c.createdAt();
    return e;
  }
}

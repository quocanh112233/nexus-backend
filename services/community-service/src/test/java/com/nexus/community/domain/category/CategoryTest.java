package com.nexus.community.domain.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm thử hành vi và sự kiện của aggregate Category
class CategoryTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Dựng một Category mới đã xóa hàng đợi sự kiện
  private static Category newCategory() {
    Category category =
        Category.create(ServerId.newId(), new CategoryName("Text"), new Position(0), NOW);
    category.pullEvents();
    return category;
  }

  // Kịch bản: đổi vị trí phát CategoryUpdated mang vị trí mới
  // Input: moveTo(Position 3, NOW+5s)
  // Output: 1 CategoryUpdated với position 3, updatedAt = NOW+5s
  @Test
  void moveToEmitsUpdated() {
    Category category = newCategory();

    category.moveTo(new Position(3), NOW.plusSeconds(5));

    assertThat(category.pullEvents())
        .singleElement()
        .isEqualTo(
            new CategoryUpdated(
                category.serverId(), category.id(), new CategoryName("Text"), new Position(3)));
    assertThat(category.updatedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  // Kịch bản: xóa category phát CategoryDeleted
  // Input: delete()
  // Output: 1 CategoryDeleted với serverId và categoryId
  @Test
  void deleteEmitsDeleted() {
    Category category = newCategory();

    category.delete();

    assertThat(category.pullEvents())
        .singleElement()
        .isEqualTo(new CategoryDeleted(category.serverId(), category.id()));
  }
}

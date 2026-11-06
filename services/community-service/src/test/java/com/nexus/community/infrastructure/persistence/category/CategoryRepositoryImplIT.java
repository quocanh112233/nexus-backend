package com.nexus.community.infrastructure.persistence.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Kiểm tra CategoryRepositoryImpl với Postgres thật
@SpringBootTest
class CategoryRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private CategoryRepository repository;
  @Autowired private ServerRepository servers;

  // Kịch bản: liệt kê category của server sắp theo position rồi id
  // Input: ba category position 2, 0, 0 lưu lần lượt c2, c0a, c0b
  // Output: thứ tự trả về là hai category position 0 theo id tăng rồi category position 2
  @Test
  void findAllByServerOrderedByPositionThenId() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Server server = Server.create(new ServerName("s"), UserId.newId(), now);
    servers.save(server);
    Category c2 = Category.create(server.id(), new CategoryName("c2"), new Position(2), now);
    Category c0a = Category.create(server.id(), new CategoryName("c0a"), new Position(0), now);
    Category c0b = Category.create(server.id(), new CategoryName("c0b"), new Position(0), now);
    repository.save(c2);
    repository.save(c0b);
    repository.save(c0a);

    assertThat(repository.findAllByServer(server.id()))
        .extracting(Category::id)
        .containsExactly(c0a.id(), c0b.id(), c2.id());
    assertThat(repository.findById(c2.id()).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("events", "updatedAt")
        .isEqualTo(c2);
  }
}

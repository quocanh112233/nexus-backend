package com.nexus.community.infrastructure.persistence.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.category.Category;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelChanges;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

// Kiểm tra ChannelRepositoryImpl với Postgres thật
@SpringBootTest
class ChannelRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private ChannelRepository repository;
  @Autowired private CategoryRepository categories;
  @Autowired private ServerRepository servers;

  // Kịch bản: xóa category đang chứa kênh
  // Input: kênh thuộc một category rồi categories.delete
  // Output: kênh vẫn còn và categoryId thành null
  @Test
  void deleteCategorySetsChannelCategoryNull() {
    Server server = savedServer();
    Category category = Category.create(server.id(), new CategoryName("c"), new Position(0), now());
    categories.save(category);
    Channel channel =
        Channel.create(
            server.id(), category.id(), new ChannelName("general"), new Position(0), now());
    repository.save(channel);

    categories.delete(category.id());

    Channel found = repository.findById(channel.id()).orElseThrow();
    assertThat(found.categoryId()).isNull();
    assertThat(found.name()).isEqualTo(channel.name());
  }

  // Kịch bản: lưu kênh có topic và category, kênh khác không category, rồi liệt kê
  // Input: hai kênh position 1 và 0, kênh position 0 có topic và category
  // Output: đọc lại khớp từng field, type TEXT, danh sách sắp theo position
  @Test
  void savesTopicAndCategory() {
    Server server = savedServer();
    Category category = Category.create(server.id(), new CategoryName("c"), new Position(0), now());
    categories.save(category);
    Channel plain =
        Channel.create(server.id(), null, new ChannelName("plain"), new Position(1), now());
    Channel rich =
        Channel.create(server.id(), category.id(), new ChannelName("rich"), new Position(0), now());
    rich.update(new ChannelChanges(null, "topic", null, false, null), now());
    repository.save(plain);
    repository.save(rich);

    assertThat(repository.findById(rich.id()).orElseThrow())
        .usingRecursiveComparison()
        .ignoringFields("events")
        .isEqualTo(rich);
    assertThat(repository.findById(plain.id()).orElseThrow().categoryId()).isNull();
    assertThat(repository.findById(plain.id()).orElseThrow().type()).isEqualTo("TEXT");
    assertThat(repository.findAllByServer(server.id()))
        .extracting(Channel::id)
        .containsExactly(rich.id(), plain.id());
  }

  // Kịch bản: hai bản cùng version cùng sửa kênh, bản lưu sau đã cũ
  // Input: tải hai bản, đổi topic cả hai, lưu bản 1 rồi bản 2
  // Output: lưu bản 2 ném ObjectOptimisticLockingFailureException
  @Test
  void staleVersionThrowsOptimisticLock() {
    Server server = savedServer();
    Channel channel =
        Channel.create(server.id(), null, new ChannelName("general"), new Position(0), now());
    repository.save(channel);
    Channel first = repository.findById(channel.id()).orElseThrow();
    Channel second = repository.findById(channel.id()).orElseThrow();
    first.update(new ChannelChanges(null, "one", null, false, null), now());
    second.update(new ChannelChanges(null, "two", null, false, null), now());

    repository.save(first);

    assertThat(repository.findById(channel.id()).orElseThrow().version()).isEqualTo(1L);
    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  // Lưu một server mới làm chỗ chứa cho kênh
  private Server savedServer() {
    Server server = Server.create(new ServerName("s"), UserId.newId(), now());
    servers.save(server);
    return server;
  }

  // Thời điểm hiện tại cắt về micro để khớp timestamptz
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}

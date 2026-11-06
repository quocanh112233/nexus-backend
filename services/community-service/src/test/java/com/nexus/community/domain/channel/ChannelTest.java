package com.nexus.community.domain.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.Position;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm thử hành vi và sự kiện của aggregate Channel
class ChannelTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Dựng một Channel thuộc category, đã xóa hàng đợi sự kiện
  private static Channel newChannel(CategoryId category) {
    Channel channel =
        Channel.create(
            ServerId.newId(), category, new ChannelName("general"), new Position(0), NOW);
    channel.pullEvents();
    return channel;
  }

  // Kịch bản: đổi cả tên, topic, vị trí chỉ phát một ChannelUpdated
  // Input: update với name "dev", topic "hi", position 2
  // Output: 1 ChannelUpdated mang giá trị mới, updatedAt = NOW+5s
  @Test
  void updateEmitsSingleEventWhenAnythingChanged() {
    CategoryId category = CategoryId.newId();
    Channel channel = newChannel(category);

    channel.update(
        new ChannelChanges(new ChannelName("dev"), "hi", new Position(2), false, null),
        NOW.plusSeconds(5));

    assertThat(channel.pullEvents())
        .singleElement()
        .isEqualTo(
            new ChannelUpdated(
                channel.serverId(),
                channel.id(),
                category,
                new ChannelName("dev"),
                "hi",
                new Position(2)));
    assertThat(channel.updatedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  // Kịch bản: thay đổi rỗng hoặc trùng giá trị hiện tại không phát sự kiện
  // Input: update toàn null, rồi update với name và position bằng hiện tại
  // Output: không có sự kiện, updatedAt giữ NOW
  @Test
  void noChangeEmitsNothing() {
    Channel channel = newChannel(CategoryId.newId());

    channel.update(new ChannelChanges(null, null, null, false, null), NOW.plusSeconds(5));
    channel.update(
        new ChannelChanges(new ChannelName("general"), null, new Position(0), false, null),
        NOW.plusSeconds(6));

    assertThat(channel.pullEvents()).isEmpty();
    assertThat(channel.updatedAt()).isEqualTo(NOW);
  }

  // Kịch bản: changeCategory=true với categoryId null gỡ kênh khỏi category
  // Input: update(changeCategory=true, categoryId=null)
  // Output: categoryId() null và ChannelUpdated có categoryId null
  @Test
  void changeCategoryToNullDetaches() {
    Channel channel = newChannel(CategoryId.newId());

    channel.update(new ChannelChanges(null, null, null, true, null), NOW.plusSeconds(5));

    assertThat(channel.categoryId()).isNull();
    assertThat(channel.pullEvents())
        .singleElement()
        .isInstanceOfSatisfying(ChannelUpdated.class, e -> assertThat(e.categoryId()).isNull());
  }

  // Kịch bản: topic dài quá 1024 ký tự bị từ chối, topic đúng 1024 được nhận
  // Input: update với topic 1025 ký tự, rồi 1024 ký tự
  // Output: lần đầu ném DomainValidationException và không đổi gì, lần sau phát sự kiện
  @Test
  void topicOver1024Rejected() {
    Channel channel = newChannel(null);

    assertThatThrownBy(
            () ->
                channel.update(new ChannelChanges(null, "x".repeat(1025), null, false, null), NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThat(channel.topic()).isNull();
    assertThat(channel.pullEvents()).isEmpty();

    channel.update(new ChannelChanges(null, "x".repeat(1024), null, false, null), NOW);
    assertThat(channel.pullEvents()).hasSize(1);
  }
}

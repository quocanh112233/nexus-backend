package com.nexus.messaging.domain.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra aggregate kênh DM và quan hệ bạn bè
class DmChannelTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private final UserPair pair =
      UserPair.of(new UserId(UUID.randomUUID()), new UserId(UUID.randomUUID()));

  // Kịch bản: mở kênh DM mới
  // Input: open(id, pair, NOW)
  // Output: đúng trường và đúng một DmChannelCreated(channelId, low, high), lần kéo sau rỗng
  @Test
  void openEmitsCreated() {
    UUID id = UUID.randomUUID();

    DmChannel channel = DmChannel.open(id, pair, NOW);

    assertThat(channel.id()).isEqualTo(id);
    assertThat(channel.pair()).isEqualTo(pair);
    assertThat(channel.createdAt()).isEqualTo(NOW);
    assertThat(channel.pullEvents())
        .containsExactly(new DmChannelCreated(new ChannelId(id), pair.low(), pair.high()));
    assertThat(channel.pullEvents()).isEmpty();
  }

  // Kịch bản: dựng lại kênh từ lưu trữ
  // Input: rehydrate(id, pair, NOW)
  // Output: đúng trường, không có sự kiện
  @Test
  void rehydrateEmitsNothing() {
    UUID id = UUID.randomUUID();

    DmChannel channel = DmChannel.rehydrate(id, pair, NOW);

    assertThat(channel.id()).isEqualTo(id);
    assertThat(channel.pair()).isEqualTo(pair);
    assertThat(channel.createdAt()).isEqualTo(NOW);
    assertThat(channel.pullEvents()).isEmpty();
  }

  // Kịch bản: tạo quan hệ bạn bè
  // Input: Friendship.create(pair, NOW)
  // Output: pair và createdAt đúng
  @Test
  void friendshipCreateKeepsFields() {
    Friendship friendship = Friendship.create(pair, NOW);

    assertThat(friendship.pair()).isEqualTo(pair);
    assertThat(friendship.createdAt()).isEqualTo(NOW);
  }
}

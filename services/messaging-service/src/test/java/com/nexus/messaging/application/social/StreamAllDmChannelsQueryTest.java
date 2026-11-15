package com.nexus.messaging.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra vòng lặp keyset của StreamAllDmChannelsQuery với repository giả
class StreamAllDmChannelsQueryTest {

  private final DmChannelRepository repo = mock(DmChannelRepository.class);
  private final StreamAllDmChannelsQuery query = new StreamAllDmChannelsQuery(repo);

  // Kịch bản: tổng số kênh đúng bằng một trang nên lô đầy rồi đến lô rỗng
  // Input: lô đầu đúng PAGE_SIZE kênh, lô kế tiếp rỗng
  // Output: sink nhận đủ PAGE_SIZE kênh không trùng, pageAll gọi đúng hai lần với cận id cuối lô
  @Test
  void exactPageMultipleStopsAfterEmptyBatchWithoutLossOrDuplicates() {
    List<DmChannel> full = new ArrayList<>();
    for (int i = 0; i < StreamAllDmChannelsQuery.PAGE_SIZE; i++) {
      full.add(
          DmChannel.rehydrate(
              UUID.randomUUID(),
              UserPair.of(new UserId(UUID.randomUUID()), new UserId(UUID.randomUUID())),
              Instant.now()));
    }
    UUID last = full.get(full.size() - 1).id();
    when(repo.pageAll(null, StreamAllDmChannelsQuery.PAGE_SIZE)).thenReturn(full);
    when(repo.pageAll(last, StreamAllDmChannelsQuery.PAGE_SIZE)).thenReturn(List.of());
    List<UUID> seen = new ArrayList<>();

    query.execute(c -> seen.add(c.id()));

    assertThat(seen).hasSize(StreamAllDmChannelsQuery.PAGE_SIZE).doesNotHaveDuplicates();
    verify(repo, times(2)).pageAll(org.mockito.ArgumentMatchers.any(), anyInt());
  }
}

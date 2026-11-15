package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn duyệt toàn bộ kênh DM theo từng lô keyset, đẩy từng kênh ra ngoài
@Service
public class StreamAllDmChannelsQuery {

  static final int PAGE_SIZE = 1000;

  private final DmChannelRepository channels;

  // Nhận repository kênh DM qua constructor
  public StreamAllDmChannelsQuery(DmChannelRepository channels) {
    this.channels = channels;
  }

  // Duyệt từng lô đến khi lô ngắn hơn kích thước trang, gọi sink cho từng kênh
  @Transactional(readOnly = true)
  public void execute(Consumer<DmChannel> sink) {
    UUID after = null;
    List<DmChannel> batch;
    do {
      batch = channels.pageAll(after, PAGE_SIZE);
      batch.forEach(sink);
      if (!batch.isEmpty()) {
        after = batch.get(batch.size() - 1).id();
      }
    } while (batch.size() == PAGE_SIZE);
  }
}

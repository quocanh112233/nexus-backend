package com.nexus.messaging.domain.channel;

import com.nexus.messaging.domain.shared.ChannelId;
import java.util.UUID;

// Cổng ghi nhớ tham chiếu kênh mà messaging đã thấy
public interface ChannelRefRepository {

  // Ghi kênh server nếu chưa có, đã có thì giữ nguyên
  void upsertServerChannel(ChannelId channelId, UUID serverId);
}

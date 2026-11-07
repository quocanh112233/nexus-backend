package com.nexus.messaging.infrastructure.persistence.channel;

import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt ChannelRefRepository bằng lệnh INSERT ... ON CONFLICT DO NOTHING
@Repository
class ChannelRefRepositoryImpl implements ChannelRefRepository {

  private final ChannelRefDao dao;

  // Nhận DAO qua constructor
  ChannelRefRepositoryImpl(ChannelRefDao dao) {
    this.dao = dao;
  }

  // Ghi kênh server nếu chưa có
  @Override
  public void upsertServerChannel(ChannelId channelId, UUID serverId) {
    dao.upsertServerChannel(channelId.value(), serverId);
  }
}

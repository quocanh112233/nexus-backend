package com.nexus.messaging.infrastructure.persistence.channel;

import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt ChannelRefRepository bằng lệnh native INSERT ... ON CONFLICT và UPDATE
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

  // Đánh dấu kênh đã xóa
  @Override
  public void tombstoneChannel(ChannelId channelId, UUID serverId, Instant at) {
    dao.tombstoneChannel(channelId.value(), serverId, at);
  }

  // Đánh dấu mọi kênh của server đã xóa
  @Override
  public void tombstoneServer(UUID serverId, Instant at) {
    dao.tombstoneServer(serverId, at);
  }

  // Kênh đã xóa hay chưa
  @Override
  public boolean isDeleted(ChannelId channelId) {
    return dao.isDeleted(channelId.value());
  }
}

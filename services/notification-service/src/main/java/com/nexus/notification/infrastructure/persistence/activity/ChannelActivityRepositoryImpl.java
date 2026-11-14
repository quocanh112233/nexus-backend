package com.nexus.notification.infrastructure.persistence.activity;

import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt ChannelActivityRepository bằng lệnh native INSERT ... ON CONFLICT và UPDATE
@Repository
class ChannelActivityRepositoryImpl implements ChannelActivityRepository {

  private final ChannelActivityDao dao;

  // Nhận DAO qua constructor
  ChannelActivityRepositoryImpl(ChannelActivityDao dao) {
    this.dao = dao;
  }

  // Ghi kênh nếu chưa có
  @Override
  public void upsertChannel(ChannelId channelId, ServerId serverId) {
    dao.upsertChannel(channelId.value(), raw(serverId));
  }

  // Đánh dấu kênh đã xóa
  @Override
  public void tombstoneChannel(ChannelId channelId, ServerId serverId, Instant at) {
    dao.tombstoneChannel(channelId.value(), raw(serverId), at);
  }

  // Đánh dấu mọi kênh của server đã xóa
  @Override
  public void tombstoneServer(ServerId serverId, Instant at) {
    dao.tombstoneServer(serverId.value(), at);
  }

  // Ghi tin mới nhất, chỉ tiến theo messageId
  @Override
  public void recordMessage(
      ChannelId channelId, ServerId serverId, MessageId messageId, Instant at) {
    dao.recordMessage(channelId.value(), raw(serverId), messageId.value(), at);
  }

  // Tìm kênh chưa bị xóa, serverId rỗng với kênh DM
  @Override
  public Optional<ChannelScope> findLiveChannel(ChannelId channelId) {
    return dao.findLive(channelId.value()).stream()
        .findFirst()
        .map(r -> new ChannelScope(channelId, r[1] == null ? null : new ServerId((UUID) r[1])));
  }

  // Mở UUID của serverId, null với kênh DM
  private static UUID raw(ServerId serverId) {
    return serverId == null ? null : serverId.value();
  }
}

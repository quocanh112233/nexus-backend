package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import java.time.Instant;
import java.util.Optional;

// Kho projection hoạt động kênh: tin mới nhất và tombstone của kênh, server
public interface ChannelActivityRepository {

  // Tạo kênh nếu chưa có, không ghi đè kênh đã tồn tại
  void upsertChannel(ChannelId channelId, ServerId serverId);

  // Đánh dấu kênh đã xóa, tạo dòng tombstone nếu ChannelDeleted đến trước ChannelCreated
  void tombstoneChannel(ChannelId channelId, ServerId serverId, Instant at);

  // Đánh dấu mọi kênh của server đã xóa
  void tombstoneServer(ServerId serverId, Instant at);

  // Ghi tin mới nhất của kênh, chỉ tiến theo messageId và tự tạo kênh nếu chưa có
  void recordMessage(ChannelId channelId, ServerId serverId, MessageId messageId, Instant at);

  // Tìm kênh chưa bị xóa
  Optional<ChannelScope> findLiveChannel(ChannelId channelId);
}

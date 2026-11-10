package com.nexus.messaging.domain.channel;

import com.nexus.messaging.domain.shared.ChannelId;
import java.time.Instant;
import java.util.UUID;

// Cổng ghi nhớ tham chiếu kênh mà messaging đã thấy
public interface ChannelRefRepository {

  // Ghi kênh server nếu chưa có, đã có thì giữ nguyên
  void upsertServerChannel(ChannelId channelId, UUID serverId);

  // Ghi kênh DM (kind DM, không server) nếu chưa có, chạy trong transaction của nơi gọi
  void upsertDmChannel(ChannelId channelId);

  // Đánh dấu kênh đã xóa, tạo dòng nếu chưa có và giữ thời điểm xóa đầu tiên
  void tombstoneChannel(ChannelId channelId, UUID serverId, Instant at);

  // Đánh dấu đã xóa mọi kênh của server, giữ thời điểm xóa đầu tiên
  void tombstoneServer(UUID serverId, Instant at);

  // True nếu kênh đã bị đánh dấu xóa
  boolean isDeleted(ChannelId channelId);

  // True nếu kênh được ghi là DM
  boolean isDm(ChannelId channelId);
}

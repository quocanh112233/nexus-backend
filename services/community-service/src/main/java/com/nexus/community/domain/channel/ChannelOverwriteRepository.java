package com.nexus.community.domain.channel;

import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Cổng lưu trữ overwrite quyền theo kênh
public interface ChannelOverwriteRepository {

  // Overwrite của một kênh
  List<ChannelOverwrite> findByChannel(ChannelId channelId);

  // Overwrite của mọi kênh trong server bằng một query, kênh không có overwrite thì vắng khỏi map
  Map<ChannelId, List<ChannelOverwrite>> findByServer(ServerId serverId);

  // Ghi overwrite, đã có cùng đích thì ghi đè
  void upsert(ChannelId channelId, ChannelOverwrite overwrite);

  // Gỡ overwrite theo đích, true khi có hàng bị xóa
  boolean remove(ChannelId channelId, OverwriteTarget type, UUID targetId);

  // Xóa mọi overwrite của role hoặc member đó trong các kênh của server
  void removeByTarget(ServerId serverId, UUID targetId);
}

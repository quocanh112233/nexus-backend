package com.nexus.community.application.server;

import java.util.List;
import java.util.UUID;

// Quyền của người gọi: bitset phạm vi server và quyền hiệu lực trên từng kênh
public record MyPermissions(long server, List<ChannelPermissions> channels) {

  // Quyền hiệu lực của người gọi trên một kênh
  public record ChannelPermissions(UUID channelId, long permissions) {}
}

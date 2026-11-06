package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.MyPermissions;
import java.util.List;
import java.util.UUID;

// Phản hồi quyền của người gọi: bitset server và quyền từng kênh
public record MyPermissionsResponse(long server, List<ChannelPermission> channels) {

  // Quyền hiệu lực trên một kênh
  public record ChannelPermission(UUID channelId, long permissions) {}

  // Dựng phản hồi từ kết quả truy vấn
  static MyPermissionsResponse from(MyPermissions p) {
    return new MyPermissionsResponse(
        p.server(),
        p.channels().stream()
            .map(c -> new ChannelPermission(c.channelId(), c.permissions()))
            .toList());
  }
}

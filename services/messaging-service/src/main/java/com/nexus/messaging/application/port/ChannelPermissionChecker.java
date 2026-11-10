package com.nexus.messaging.application.port;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;

// Cổng hỏi quyền của user trên kênh: DM tính tại chỗ, kênh server hỏi Community
public interface ChannelPermissionChecker {

  // Trả quyền của user, ném ChannelNotFoundException hoặc DependencyUnavailableException
  ChannelAccess check(UserId userId, ChannelId channelId);
}

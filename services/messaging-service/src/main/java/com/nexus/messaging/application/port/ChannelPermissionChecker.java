package com.nexus.messaging.application.port;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;

// Cổng hỏi Community quyền của user trên kênh
public interface ChannelPermissionChecker {

  // Trả quyền của user, ném ChannelNotFoundException hoặc DependencyUnavailableException
  ChannelAccess check(UserId userId, ChannelId channelId);
}

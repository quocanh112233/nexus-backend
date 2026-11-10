package com.nexus.realtime.application.subscription;

import java.util.Optional;
import java.util.UUID;

// Cổng hỏi quyền của user với kênh và server, lỗi hạ tầng thì ném AccessCheckUnavailableException
public interface AccessChecker {

  // Phạm vi kênh (serverId null là DM) nếu user xem được, rỗng nếu không xem được hoặc không có
  Optional<ChannelScope> canViewChannel(UUID userId, UUID channelId);

  // User có là member của server không
  boolean isServerMember(UUID userId, UUID serverId);
}

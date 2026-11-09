package com.nexus.realtime.application.subscription;

import java.util.Optional;
import java.util.UUID;

// Cổng hỏi quyền của user với kênh và server, lỗi hạ tầng thì ném AccessCheckUnavailableException
public interface AccessChecker {

  // Id server của kênh nếu user có VIEW_CHANNEL, rỗng nếu không được xem hoặc kênh không tồn tại
  Optional<UUID> canViewChannel(UUID userId, UUID channelId);

  // User có là member của server không
  boolean isServerMember(UUID userId, UUID serverId);
}

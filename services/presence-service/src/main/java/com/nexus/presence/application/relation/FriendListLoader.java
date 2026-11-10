package com.nexus.presence.application.relation;

import java.util.Set;
import java.util.UUID;

// Cổng nạp danh sách bạn của user từ Messaging
public interface FriendListLoader {

  // Id mọi bạn của user, lỗi hạ tầng thì ném RelationUnavailableException
  Set<UUID> friendIdsOf(UUID userId);
}

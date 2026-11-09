package com.nexus.presence.application.relation;

import java.util.Set;
import java.util.UUID;

// Cổng nạp danh sách server của user từ Community
public interface ServerListLoader {

  // Id mọi server user là thành viên, lỗi hạ tầng thì ném RelationUnavailableException
  Set<UUID> serverIdsOf(UUID userId);
}

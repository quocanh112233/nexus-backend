package com.nexus.messaging.application.port;

import java.util.UUID;

// Cổng tăng phiên bản quyền của một server để vô hiệu cache quyền
public interface PermissionVersion {

  // Tăng phiên bản quyền của server
  void bump(UUID serverId);
}

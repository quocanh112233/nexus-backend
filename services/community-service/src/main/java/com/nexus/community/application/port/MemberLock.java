package com.nexus.community.application.port;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;

// Cổng khóa theo cặp (server,user), chỉ dùng trong use case @Transactional, nhả khi commit/rollback
public interface MemberLock {

  // Chờ và giữ khóa của cặp (server,user) đến hết transaction hiện tại
  void lock(ServerId serverId, UserId userId);
}

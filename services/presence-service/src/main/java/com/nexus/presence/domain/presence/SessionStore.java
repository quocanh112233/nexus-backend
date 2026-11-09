package com.nexus.presence.domain.presence;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Kho session của user theo instance gateway, mọi thao tác nguyên tử và idempotent
public interface SessionStore {

  // Ghi session, true nếu user từ 0 lên 1 session; session trùng hoặc đã đóng thì false
  boolean connect(String sessionId, UUID userId, String instanceId, Instant connectedAt);

  // Bỏ session và ghi dấu đã đóng, trả user nếu user vừa về 0 session
  Optional<UUID> disconnect(String sessionId);

  // Gia hạn khóa còn sống của instance
  void markAlive(String instanceId);

  // Session của instance có connectedAt không muộn hơn mốc
  Set<String> sessionsOf(String instanceId, Instant connectedUpTo);

  // Instance đã đăng ký nhưng mất khóa còn sống
  Set<String> deadInstances();

  // User có ít nhất một session không (khóa presence:status còn)
  boolean isOnline(UUID userId);

  // Xóa mọi session của instance, trả các user vừa về 0 session
  Set<UUID> purge(String instanceId);
}

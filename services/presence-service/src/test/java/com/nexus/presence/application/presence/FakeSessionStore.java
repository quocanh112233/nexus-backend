package com.nexus.presence.application.presence;

import com.nexus.presence.domain.presence.SessionStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Store session trong bộ nhớ mô phỏng hợp đồng của script Lua, không có tombstone
public class FakeSessionStore implements SessionStore {

  // Một session giả gồm user, instance và thời điểm kết nối
  private record Session(UUID userId, String instanceId, Instant connectedAt) {}

  private final Map<String, Session> sessions = new HashMap<>();
  private final Set<String> alive = new HashSet<>();

  // Thêm session, trả true nếu user từ 0 lên 1 session
  @Override
  public boolean connect(String sessionId, UUID userId, String instanceId, Instant connectedAt) {
    if (sessions.containsKey(sessionId)) {
      return false;
    }
    boolean first = sessionsOfUser(userId) == 0;
    sessions.put(sessionId, new Session(userId, instanceId, connectedAt));
    return first;
  }

  // Bỏ session, trả user nếu user về 0 session
  @Override
  public Optional<UUID> disconnect(String sessionId) {
    Session s = sessions.remove(sessionId);
    return s == null || sessionsOfUser(s.userId()) > 0 ? Optional.empty() : Optional.of(s.userId());
  }

  // Ghi nhận instance còn sống
  @Override
  public void markAlive(String instanceId) {
    alive.add(instanceId);
  }

  // Session của instance có connectedAt không muộn hơn mốc
  @Override
  public Set<String> sessionsOf(String instanceId, Instant connectedUpTo) {
    return sessions.entrySet().stream()
        .filter(e -> e.getValue().instanceId().equals(instanceId))
        .filter(e -> !e.getValue().connectedAt().isAfter(connectedUpTo))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  // Instance có session nhưng không được đánh dấu còn sống
  @Override
  public Set<String> deadInstances() {
    return sessions.values().stream()
        .map(Session::instanceId)
        .filter(i -> !alive.contains(i))
        .collect(Collectors.toSet());
  }

  // Bỏ mọi session của instance, trả user về 0 session; instance đã sống lại thì không làm gì
  @Override
  public Set<UUID> purge(String instanceId) {
    if (alive.contains(instanceId)) {
      return Set.of();
    }
    Set<UUID> users = new HashSet<>();
    sessionsOf(instanceId, Instant.MAX).forEach(sid -> disconnect(sid).ifPresent(users::add));
    return users;
  }

  // User có session nào không
  @Override
  public boolean isOnline(UUID userId) {
    return sessionsOfUser(userId) > 0;
  }

  // Số session đang có của user
  private long sessionsOfUser(UUID userId) {
    return sessions.values().stream().filter(s -> s.userId().equals(userId)).count();
  }
}

package com.nexus.presence.infrastructure.redis;

import com.nexus.presence.domain.presence.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Kho session trên Redis theo data-model §5, ghi bằng Lua nguyên tử; instance sessions là zset
@Component
public class RedisSessionStore implements SessionStore {

  private static final String INSTANCES = "presence:instances";
  private static final long TOMBSTONE_MILLIS = 60_000;

  private final StringRedisTemplate redis;
  private final Duration aliveTtl;

  // Nhận template Redis chuỗi và TTL khóa alive (mặc định 60 giây)
  public RedisSessionStore(
      StringRedisTemplate redis, @Value("${app.presence.alive-ttl:60s}") Duration aliveTtl) {
    this.redis = redis;
    this.aliveTtl = aliveTtl;
  }

  // Chạy session-connect.lua, trả true nếu user vừa lên ONLINE
  @Override
  public boolean connect(String sessionId, UUID userId, String instanceId, Instant connectedAt) {
    Long r =
        redis.execute(
            SessionScripts.CONNECT,
            List.of(
                session(sessionId),
                "presence:user:" + userId + ":sessions",
                instanceSessions(instanceId),
                "presence:status:" + userId,
                closed(sessionId),
                INSTANCES,
                alive(instanceId)),
            sessionId,
            userId.toString(),
            instanceId,
            String.valueOf(connectedAt.toEpochMilli()),
            String.valueOf(aliveTtl.toMillis()));
    return r != null && r == 1;
  }

  // Chạy session-disconnect.lua, trả user nếu vừa về OFFLINE
  @Override
  public Optional<UUID> disconnect(String sessionId) {
    String user =
        redis.execute(
            SessionScripts.DISCONNECT,
            List.of(session(sessionId), closed(sessionId)),
            sessionId,
            String.valueOf(TOMBSTONE_MILLIS));
    return Optional.ofNullable(user).map(UUID::fromString);
  }

  // Đặt khóa alive của instance với TTL cấu hình
  @Override
  public void markAlive(String instanceId) {
    redis.opsForValue().set(alive(instanceId), "1", aliveTtl);
  }

  // ZRANGEBYSCORE theo thời điểm connect của session trong instance
  @Override
  public Set<String> sessionsOf(String instanceId, Instant connectedUpTo) {
    Set<String> ids =
        redis
            .opsForZSet()
            .rangeByScore(
                instanceSessions(instanceId),
                Double.NEGATIVE_INFINITY,
                connectedUpTo.toEpochMilli());
    return ids == null ? Set.of() : ids;
  }

  // Instance trong danh sách đăng ký mà khóa alive đã hết hạn
  @Override
  public Set<String> deadInstances() {
    Set<String> all = redis.opsForSet().members(INSTANCES);
    return all == null
        ? Set.of()
        : all.stream().filter(i -> !redis.hasKey(alive(i))).collect(Collectors.toSet());
  }

  // Chạy instance-purge.lua, trả các user vừa về OFFLINE; instance đã sống lại thì trả rỗng
  @Override
  public Set<UUID> purge(String instanceId) {
    List<?> users =
        redis.execute(
            SessionScripts.PURGE,
            List.of(instanceSessions(instanceId), INSTANCES, alive(instanceId)),
            instanceId);
    return users == null
        ? Set.of()
        : users.stream().map(u -> UUID.fromString(u.toString())).collect(Collectors.toSet());
  }

  // Khóa presence:status có tồn tại không
  @Override
  public boolean isOnline(UUID userId) {
    return redis.hasKey("presence:status:" + userId);
  }

  // Key session
  private static String session(String sessionId) {
    return "presence:session:" + sessionId;
  }

  // Key dấu đã đóng của session
  private static String closed(String sessionId) {
    return "presence:closed:" + sessionId;
  }

  // Key zset session của instance
  private static String instanceSessions(String instanceId) {
    return "presence:instance:" + instanceId + ":sessions";
  }

  // Key còn sống của instance
  private static String alive(String instanceId) {
    return "presence:instance:" + instanceId + ":alive";
  }
}

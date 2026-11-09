package com.nexus.presence.infrastructure.redis;

import com.nexus.presence.domain.relation.RelationshipProjection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// Projection quan hệ user-server trên Redis theo data-model §5, ghi có điều kiện bằng Lua
@Component
public class RedisRelationshipProjection implements RelationshipProjection {

  private static final Duration LOADED_TTL = Duration.ofHours(24);
  private static final Duration EMPTY_LOADED_TTL = Duration.ofMinutes(5);
  private static final Duration SERVERS_TTL = Duration.ofHours(25);
  private static final DefaultRedisScript<Long> LOAD =
      new DefaultRedisScript<>(
          "redis.call('DEL', KEYS[1])"
              + " for i = 4, #ARGV do"
              + " redis.call('SADD', KEYS[1], ARGV[i])"
              + " redis.call('SADD', KEYS[i - 1], ARGV[1]) end"
              + " redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])"
              + " if #ARGV > 3 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end return 1",
          Long.class);
  private static final DefaultRedisScript<Long> ADD = script("SADD");
  private static final DefaultRedisScript<Long> REMOVE = script("SREM");
  private static final DefaultRedisScript<Long> SHARE =
      new DefaultRedisScript<>(
          "return redis.call('SINTERCARD', 2, KEYS[1], KEYS[2], 'LIMIT', 1)", Long.class);

  private final StringRedisTemplate redis;

  // Nhận template Redis chuỗi
  public RedisRelationshipProjection(StringRedisTemplate redis) {
    this.redis = redis;
  }

  // Tập server của user
  @Override
  public Set<UUID> serversOf(UUID userId) {
    return uuids(redis.opsForSet().members(servers(userId)));
  }

  // Cờ loaded còn hiệu lực không
  @Override
  public boolean isLoaded(UUID userId) {
    return redis.hasKey(loaded(userId));
  }

  // Một Lua: thay tập server, ghi members, đặt cờ loaded 24 giờ (5 phút nếu rỗng), servers 25 giờ
  @Override
  public void load(UUID userId, Set<UUID> serverIds) {
    List<String> keys = new ArrayList<>(List.of(servers(userId), loaded(userId)));
    serverIds.forEach(s -> keys.add(members(s)));
    List<String> args = new ArrayList<>();
    args.add(userId.toString());
    args.add(Long.toString((serverIds.isEmpty() ? EMPTY_LOADED_TTL : LOADED_TTL).toSeconds()));
    args.add(Long.toString(SERVERS_TTL.toSeconds()));
    serverIds.forEach(s -> args.add(s.toString()));
    redis.execute(LOAD, keys, args.toArray());
  }

  // SADD members luôn chạy, SADD tập server của user chỉ khi cờ loaded còn
  @Override
  public void addServer(UUID userId, UUID serverId) {
    redis.execute(ADD, keys(userId, serverId), userId.toString(), serverId.toString());
  }

  // SREM members luôn chạy, SREM tập server của user chỉ khi cờ loaded còn
  @Override
  public void removeServer(UUID userId, UUID serverId) {
    redis.execute(REMOVE, keys(userId, serverId), userId.toString(), serverId.toString());
  }

  // Các user được ghi nhận là member của server
  @Override
  public Set<UUID> membersOf(UUID serverId) {
    return uuids(redis.opsForSet().members(members(serverId)));
  }

  // Xóa tập members của server
  @Override
  public void deleteServer(UUID serverId) {
    redis.delete(members(serverId));
  }

  // SINTERCARD 2 hai tập server LIMIT 1, lớn hơn 0 là có chung server
  @Override
  public boolean shareServer(UUID a, UUID b) {
    Long n = redis.execute(SHARE, List.of(servers(a), servers(b)));
    return n != null && n > 0;
  }

  // Lua: lệnh trên members luôn chạy, cùng lệnh trên tập server của user nếu cờ loaded còn
  private static DefaultRedisScript<Long> script(String command) {
    return new DefaultRedisScript<>(
        ("redis.call('%1$s', KEYS[1], ARGV[1])"
                + " if redis.call('EXISTS', KEYS[3]) == 1 then"
                + " redis.call('%1$s', KEYS[2], ARGV[2]) end return 1")
            .formatted(command),
        Long.class);
  }

  // KEYS cho script: members của server, tập server của user, cờ loaded của user
  private static List<String> keys(UUID userId, UUID serverId) {
    return List.of(members(serverId), servers(userId), loaded(userId));
  }

  // Key tập server của user
  private static String servers(UUID userId) {
    return "presence:rel:" + userId + ":servers";
  }

  // Key cờ loaded của user
  private static String loaded(UUID userId) {
    return "presence:rel:" + userId + ":loaded";
  }

  // Key tập members của server
  private static String members(UUID serverId) {
    return "presence:server:" + serverId + ":members";
  }

  // Đổi tập chuỗi thành tập UUID, null thành rỗng
  private static Set<UUID> uuids(Set<String> values) {
    return values == null
        ? Set.of()
        : values.stream().map(UUID::fromString).collect(Collectors.toSet());
  }
}

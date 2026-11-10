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

// Projection quan hệ user-server và bạn bè trên Redis theo data-model §5, ghi có điều kiện bằng Lua
@Component
public class RedisRelationshipProjection implements RelationshipProjection {

  private static final Duration LOADED_TTL = Duration.ofHours(24);
  private static final Duration EMPTY_LOADED_TTL = Duration.ofMinutes(5);
  private static final Duration SERVERS_TTL = Duration.ofHours(25);
  private static final String EPOCH = "presence:rel:epoch";
  private static final String REVISION_SUM =
      "(tonumber(redis.call('GET', KEYS[%d])) or 0) + (tonumber(redis.call('GET', KEYS[%d])) or 0)";
  private static final DefaultRedisScript<Long> LOAD =
      new DefaultRedisScript<>(
          ("if "
              + REVISION_SUM.formatted(3, 4)
              + " ~= tonumber(ARGV[4]) then return 0 end"
              + " local n = tonumber(ARGV[5])"
              + " redis.call('DEL', KEYS[1], KEYS[5])"
              + " for i = 6, 5 + n do"
              + " redis.call('SADD', KEYS[1], ARGV[i])"
              + " redis.call('SADD', KEYS[i], ARGV[1]) end"
              + " for i = 6 + n, #ARGV do redis.call('SADD', KEYS[5], ARGV[i]) end"
              + " redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])"
              + " if n > 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end"
              + " if #ARGV > 5 + n then redis.call('EXPIRE', KEYS[5], ARGV[3]) end return 1"),
          Long.class);
  private static final DefaultRedisScript<Long> REVISION =
      new DefaultRedisScript<>("return " + REVISION_SUM.formatted(1, 2), Long.class);
  private static final DefaultRedisScript<List> DELETE_SERVER =
      new DefaultRedisScript<>(
          "local m = redis.call('SMEMBERS', KEYS[1])"
              + " redis.call('DEL', KEYS[1]) redis.call('INCR', KEYS[2]) return m",
          List.class);
  private static final DefaultRedisScript<Long> ADD = script("SADD");
  private static final DefaultRedisScript<Long> REMOVE = script("SREM");
  private static final DefaultRedisScript<Long> ADD_FRIEND = friendScript("SADD");
  private static final DefaultRedisScript<Long> REMOVE_FRIEND = friendScript("SREM");
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

  // Tổng rev của user và epoch, hai bộ đếm chỉ tăng nên tổng đổi khi và chỉ khi một trong hai đổi
  @Override
  public long revision(UUID userId) {
    Long rev = redis.execute(REVISION, List.of(rev(userId), EPOCH));
    return rev == null ? 0 : rev;
  }

  // Một Lua: đúng phiên bản mới thay tập server, bạn và members; loaded 24 giờ (5 phút nếu rỗng)
  @Override
  public boolean load(
      UUID userId, Set<UUID> serverIds, Set<UUID> friendIds, long expectedRevision) {
    List<String> keys =
        new ArrayList<>(
            List.of(servers(userId), loaded(userId), rev(userId), EPOCH, friends(userId)));
    serverIds.forEach(s -> keys.add(members(s)));
    boolean empty = serverIds.isEmpty() && friendIds.isEmpty();
    List<String> args = new ArrayList<>();
    args.add(userId.toString());
    args.add(Long.toString((empty ? EMPTY_LOADED_TTL : LOADED_TTL).toSeconds()));
    args.add(Long.toString(SERVERS_TTL.toSeconds()));
    args.add(Long.toString(expectedRevision));
    args.add(Integer.toString(serverIds.size()));
    serverIds.forEach(s -> args.add(s.toString()));
    friendIds.forEach(f -> args.add(f.toString()));
    return Long.valueOf(1).equals(redis.execute(LOAD, keys, args.toArray()));
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

  // Một Lua: SMEMBERS rồi DEL tập members của server và INCR epoch toàn cục, trả members đã lấy
  @Override
  @SuppressWarnings("unchecked")
  public Set<UUID> deleteServer(UUID serverId) {
    List<String> captured = redis.execute(DELETE_SERVER, List.of(members(serverId), EPOCH));
    return captured == null ? Set.of() : uuids(Set.copyOf(captured));
  }

  // SINTERCARD 2 hai tập server LIMIT 1, lớn hơn 0 là có chung server
  @Override
  public boolean shareServer(UUID a, UUID b) {
    Long n = redis.execute(SHARE, List.of(servers(a), servers(b)));
    return n != null && n > 0;
  }

  // Tập bạn của user
  @Override
  public Set<UUID> friendsOf(UUID userId) {
    return uuids(redis.opsForSet().members(friends(userId)));
  }

  // Một Lua cho cả hai phía: INCR rev hai user, SADD tập bạn của phía nào còn cờ loaded
  @Override
  public void addFriend(UUID a, UUID b) {
    redis.execute(ADD_FRIEND, friendKeys(a, b), a.toString(), b.toString());
  }

  // Một Lua cho cả hai phía: INCR rev hai user, SREM tập bạn của phía nào còn cờ loaded
  @Override
  public void removeFriend(UUID a, UUID b) {
    redis.execute(REMOVE_FRIEND, friendKeys(a, b), a.toString(), b.toString());
  }

  // SISMEMBER tập bạn của a
  @Override
  public boolean isFriend(UUID a, UUID b) {
    return Boolean.TRUE.equals(redis.opsForSet().isMember(friends(a), b.toString()));
  }

  // Lua: tăng rev của user (25 giờ), lệnh trên members luôn chạy, trên tập server nếu cờ loaded còn
  private static DefaultRedisScript<Long> script(String command) {
    return new DefaultRedisScript<>(
        ("redis.call('INCR', KEYS[4]) redis.call('EXPIRE', KEYS[4], %2$d)"
                + " redis.call('%1$s', KEYS[1], ARGV[1])"
                + " if redis.call('EXISTS', KEYS[3]) == 1 then"
                + " redis.call('%1$s', KEYS[2], ARGV[2]) end return 1")
            .formatted(command, SERVERS_TTL.toSeconds()),
        Long.class);
  }

  // Lua: tăng rev của a và b (25 giờ), lệnh trên tập bạn của phía loaded, SADD kèm đặt TTL 25 giờ
  private static DefaultRedisScript<Long> friendScript(String command) {
    String expire = command.equals("SADD") ? " redis.call('EXPIRE', KEYS[%d], %d)" : "";
    long ttl = SERVERS_TTL.toSeconds();
    return new DefaultRedisScript<>(
        ("for i = 1, 2 do redis.call('INCR', KEYS[i]) redis.call('EXPIRE', KEYS[i], %2$d) end"
                + " if redis.call('EXISTS', KEYS[3]) == 1 then"
                + " redis.call('%1$s', KEYS[5], ARGV[2])"
                + expire.formatted(5, ttl)
                + " end"
                + " if redis.call('EXISTS', KEYS[4]) == 1 then"
                + " redis.call('%1$s', KEYS[6], ARGV[1])"
                + expire.formatted(6, ttl)
                + " end return 1")
            .formatted(command, ttl),
        Long.class);
  }

  // KEYS cho script bạn bè: rev, cờ loaded và tập bạn của a rồi của b
  private static List<String> friendKeys(UUID a, UUID b) {
    return List.of(rev(a), rev(b), loaded(a), loaded(b), friends(a), friends(b));
  }

  // Key tập bạn của user
  private static String friends(UUID userId) {
    return "presence:rel:" + userId + ":friends";
  }

  // KEYS cho script: members của server, tập server, cờ loaded và rev của user
  private static List<String> keys(UUID userId, UUID serverId) {
    return List.of(members(serverId), servers(userId), loaded(userId), rev(userId));
  }

  // Key tập server của user
  private static String servers(UUID userId) {
    return "presence:rel:" + userId + ":servers";
  }

  // Key cờ loaded của user
  private static String loaded(UUID userId) {
    return "presence:rel:" + userId + ":loaded";
  }

  // Key bộ đếm phiên bản quan hệ của user
  private static String rev(UUID userId) {
    return "presence:rel:" + userId + ":rev";
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

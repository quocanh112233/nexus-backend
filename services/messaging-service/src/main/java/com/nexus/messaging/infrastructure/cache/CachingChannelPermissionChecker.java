package com.nexus.messaging.infrastructure.cache;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Cache Redis cache-aside có version theo server bọc ChannelPermissionChecker, lỗi thì gọi delegate
public class CachingChannelPermissionChecker implements ChannelPermissionChecker {

  private static final Logger LOG = LoggerFactory.getLogger(CachingChannelPermissionChecker.class);
  private static final ChannelPermission[] PERMISSIONS = ChannelPermission.values();

  private final ChannelPermissionChecker delegate;
  private final StringRedisTemplate redis;
  private final Duration ttl;
  private final Counter hits;
  private final Counter misses;

  // Nhận delegate, template Redis, registry metric và TTL của entry
  public CachingChannelPermissionChecker(
      ChannelPermissionChecker delegate,
      StringRedisTemplate redis,
      MeterRegistry registry,
      Duration ttl) {
    this.delegate = delegate;
    this.redis = redis;
    this.ttl = ttl;
    this.hits = registry.counter("nexus.permission.cache.hits");
    this.misses = registry.counter("nexus.permission.cache.misses");
  }

  // Trả từ cache nếu có, ngược lại hỏi delegate và chỉ lưu kết quả thành công
  @Override
  public ChannelAccess check(UserId userId, ChannelId channelId) {
    String key = "perm:" + channelId.value() + ":" + userId.value();
    ChannelAccess cached = read(key);
    if (cached != null) {
      hits.increment();
      return cached;
    }
    misses.increment();
    ChannelAccess access = delegate.check(userId, channelId);
    write(key, access);
    return access;
  }

  // Đọc entry, trả null nếu thiếu, hỏng định dạng, lệch phiên bản hoặc Redis lỗi
  private ChannelAccess read(String key) {
    try {
      String value = redis.opsForValue().get(key);
      return value == null ? null : decode(value);
    } catch (RuntimeException e) {
      LOG.warn("Redis permission cache read failed, falling back to delegate: {}", e.toString());
      return null;
    }
  }

  // Ghi entry kèm phiên bản hiện tại và TTL, Redis lỗi thì chỉ log
  private void write(String key, ChannelAccess access) {
    try {
      // ponytail: cửa sổ stale cực nhỏ giữa delegate trả và đọc version, tối đa 60 s; cần thì Lua
      long version = version(access.serverId());
      redis.opsForValue().set(key, encode(access, version), ttl);
    } catch (RuntimeException e) {
      LOG.warn("Redis permission cache write failed: {}", e.toString());
    }
  }

  // Phiên bản quyền hiện tại của server, thiếu khóa thì là 0
  private long version(UUID serverId) {
    String value = redis.opsForValue().get("perm:ver:" + serverId);
    return value == null ? 0 : Long.parseLong(value);
  }

  // Mã hóa thành "{serverId}|{bitmask theo ordinal}|{phiên bản}"
  private static String encode(ChannelAccess access, long version) {
    int bits = 0;
    for (ChannelPermission p : access.granted()) {
      bits |= 1 << p.ordinal();
    }
    return access.serverId() + "|" + bits + "|" + version;
  }

  // Giải mã entry, trả null nếu sai định dạng, bitmask ngoài khoảng hợp lệ hoặc lệch phiên bản
  private ChannelAccess decode(String value) {
    try {
      String[] parts = value.split("\\|", -1);
      if (parts.length != 3) {
        return null;
      }
      UUID serverId = UUID.fromString(parts[0]);
      int bits = Integer.parseInt(parts[1]);
      if (bits < 0 || bits >= (1 << PERMISSIONS.length)) {
        return null;
      }
      if (Long.parseLong(parts[2]) != version(serverId)) {
        return null;
      }
      Set<ChannelPermission> granted = EnumSet.noneOf(ChannelPermission.class);
      for (ChannelPermission p : PERMISSIONS) {
        if ((bits & (1 << p.ordinal())) != 0) {
          granted.add(p);
        }
      }
      return new ChannelAccess(serverId, granted);
    } catch (RuntimeException e) {
      return null;
    }
  }
}

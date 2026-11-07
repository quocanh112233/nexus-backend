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

// Bọc ChannelPermissionChecker bằng cache Redis cache-aside, Redis lỗi thì gọi thẳng delegate
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

  // Đọc entry, trả null nếu thiếu, hỏng định dạng hoặc Redis lỗi
  private ChannelAccess read(String key) {
    try {
      String value = redis.opsForValue().get(key);
      return value == null ? null : decode(value);
    } catch (RuntimeException e) {
      LOG.warn("Redis permission cache read failed, falling back to delegate: {}", e.toString());
      return null;
    }
  }

  // Ghi entry kèm TTL, Redis lỗi thì chỉ log
  private void write(String key, ChannelAccess access) {
    try {
      redis.opsForValue().set(key, encode(access), ttl);
    } catch (RuntimeException e) {
      LOG.warn("Redis permission cache write failed: {}", e.toString());
    }
  }

  // Mã hóa thành "{serverId}|{bitmask theo ordinal}"
  private static String encode(ChannelAccess access) {
    int bits = 0;
    for (ChannelPermission p : access.granted()) {
      bits |= 1 << p.ordinal();
    }
    return access.serverId() + "|" + bits;
  }

  // Giải mã entry, trả null nếu sai định dạng hoặc bitmask ngoài khoảng hợp lệ
  private static ChannelAccess decode(String value) {
    try {
      int sep = value.indexOf('|');
      UUID serverId = UUID.fromString(value.substring(0, sep));
      int bits = Integer.parseInt(value.substring(sep + 1));
      if (bits < 0 || bits >= (1 << PERMISSIONS.length)) {
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

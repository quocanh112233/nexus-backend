package com.nexus.messaging.infrastructure.cache;

import com.nexus.messaging.application.port.PermissionVersion;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Phiên bản quyền theo server là bộ đếm Redis perm:ver:{serverId}
@Component
public class RedisPermissionVersion implements PermissionVersion {

  private static final Logger LOG = LoggerFactory.getLogger(RedisPermissionVersion.class);

  private final StringRedisTemplate redis;

  // Nhận template Redis
  public RedisPermissionVersion(StringRedisTemplate redis) {
    this.redis = redis;
  }

  // INCR perm:ver:{serverId}, lỗi thì ném để retry; có transaction thì INCR lại sau commit
  @Override
  public void bump(UUID serverId) {
    String key = "perm:ver:" + serverId;
    redis.opsForValue().increment(key);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            // INCR lại sau commit để OK cũ chen giữa không nằm dưới version mới, lỗi thì chỉ log
            @Override
            public void afterCommit() {
              try {
                redis.opsForValue().increment(key);
              } catch (RuntimeException e) {
                LOG.warn("Redis permission version afterCommit bump failed: {}", e.toString());
              }
            }
          });
    }
  }
}

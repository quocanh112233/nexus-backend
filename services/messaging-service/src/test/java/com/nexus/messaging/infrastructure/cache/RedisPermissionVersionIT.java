package com.nexus.messaging.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.MessagingIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra bump phiên bản quyền trên Redis thật: INCR trong transaction và INCR lại sau commit
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class RedisPermissionVersionIT extends MessagingIntegrationTest {

  @Autowired private StringRedisTemplate redis;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private RedisPermissionVersion version;

  // Kịch bản: bump trong transaction rồi commit
  // Input: bump(serverId) trong TransactionTemplate, đọc perm:ver trước và sau commit
  // Output: trước commit là "1" (INCR trong transaction), sau commit là "2" (INCR afterCommit)
  @Test
  void bumpIncrementsAgainAfterCommit() {
    UUID server = UUID.randomUUID();
    String key = "perm:ver:" + server;

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              version.bump(server);
              assertThat(redis.opsForValue().get(key)).isEqualTo("1");
            });

    assertThat(redis.opsForValue().get(key)).isEqualTo("2");
  }

  // Kịch bản: bump trong transaction bị rollback
  // Input: bump(serverId) trong TransactionTemplate rồi setRollbackOnly
  // Output: perm:ver dừng ở "1", không có INCR afterCommit
  @Test
  void rollbackSkipsAfterCommitBump() {
    UUID server = UUID.randomUUID();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              version.bump(server);
              status.setRollbackOnly();
            });

    assertThat(redis.opsForValue().get("perm:ver:" + server)).isEqualTo("1");
  }
}

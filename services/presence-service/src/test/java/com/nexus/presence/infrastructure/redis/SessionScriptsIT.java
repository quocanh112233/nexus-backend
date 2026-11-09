package com.nexus.presence.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.testsupport.RedisContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Kiểm tra các script Lua session trên Redis thật: chuyển trạng thái, thứ tự đảo và trùng event
class SessionScriptsIT {

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;
  private static RedisSessionStore store;

  private final UUID user = UUID.randomUUID();
  private final String inst = "i-" + UUID.randomUUID();
  private final String s1 = "s1-" + UUID.randomUUID();
  private final String s2 = "s2-" + UUID.randomUUID();
  private final Instant at = Instant.now();

  // Dựng client Lettuce tới container Redis
  @BeforeAll
  static void connect() {
    factory = new LettuceConnectionFactory(RedisContainer.host(), RedisContainer.port());
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    store = new RedisSessionStore(redis, Duration.ofSeconds(90));
  }

  // Đóng kết nối Redis
  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  // Kịch bản: session đầu tiên của user
  // Input: connect(s1, user, inst)
  // Output: trả true (OFFLINE sang ONLINE), status ONLINE, session lưu userId|instanceId
  @Test
  void firstSessionReturnsOnlineTransition() {
    assertThat(store.connect(s1, user, inst, at)).isTrue();

    assertThat(redis.opsForValue().get("presence:status:" + user)).isEqualTo("ONLINE");
    assertThat(redis.opsForValue().get("presence:session:" + s1)).isEqualTo(user + "|" + inst);
    assertThat(redis.opsForSet().members("presence:user:" + user + ":sessions")).contains(s1);
  }

  // Kịch bản: user đã online mở thêm thiết bị
  // Input: connect s1 rồi connect s2 cùng user
  // Output: lần hai trả false, user có 2 session
  @Test
  void secondSessionOfSameUserNoTransition() {
    store.connect(s1, user, inst, at);

    assertThat(store.connect(s2, user, inst, at)).isFalse();

    assertThat(redis.opsForSet().size("presence:user:" + user + ":sessions")).isEqualTo(2);
  }

  // Kịch bản: đóng session cuối cùng của user
  // Input: connect s1, s2 rồi disconnect s1, disconnect s2
  // Output: s1 không đổi trạng thái, s2 trả user, status bị xóa, tombstone có TTL tối đa 60 giây
  @Test
  void lastDisconnectReturnsOfflineTransition() {
    store.connect(s1, user, inst, at);
    store.connect(s2, user, inst, at);

    assertThat(store.disconnect(s1)).isEmpty();
    assertThat(store.disconnect(s2)).contains(user);

    assertThat(redis.hasKey("presence:status:" + user)).isFalse();
    assertThat(redis.hasKey("presence:session:" + s2)).isFalse();
    Long ttl = redis.getExpire("presence:closed:" + s2, TimeUnit.SECONDS);
    assertThat(ttl).isBetween(1L, 60L);
  }

  // Kịch bản: SessionDisconnected bị giao trùng
  // Input: connect s1, disconnect s1 hai lần
  // Output: lần đầu trả user, lần hai rỗng
  @Test
  void disconnectTwiceIsNoOp() {
    store.connect(s1, user, inst, at);

    assertThat(store.disconnect(s1)).contains(user);
    assertThat(store.disconnect(s1)).isEmpty();
  }

  // Kịch bản: đóng session chưa từng biết
  // Input: disconnect session lạ
  // Output: rỗng, không tạo status nào
  @Test
  void disconnectUnknownSessionIsNoOp() {
    assertThat(store.disconnect(s1)).isEmpty();
    assertThat(redis.hasKey("presence:status:" + user)).isFalse();
  }

  // Kịch bản: SessionConnected bị giao trùng
  // Input: connect s1 hai lần
  // Output: lần đầu true, lần hai false, user vẫn đúng 1 session
  @Test
  void duplicateConnectIsNoOp() {
    assertThat(store.connect(s1, user, inst, at)).isTrue();
    assertThat(store.connect(s1, user, inst, at)).isFalse();

    assertThat(redis.opsForSet().size("presence:user:" + user + ":sessions")).isEqualTo(1);
  }

  // Kịch bản: SessionDisconnected đến trước SessionConnected của cùng session
  // Input: disconnect(s1) rồi connect(s1)
  // Output: connect trả false, không có status ONLINE, không có session ma
  @Test
  void disconnectBeforeConnectLeavesNoGhostSession() {
    store.disconnect(s1);

    assertThat(store.connect(s1, user, inst, at)).isFalse();

    assertThat(redis.hasKey("presence:status:" + user)).isFalse();
    assertThat(redis.hasKey("presence:session:" + s1)).isFalse();
  }

  // Kịch bản: instance mới chưa kịp heartbeat nhưng đã có session
  // Input: connect session cho instance chưa có khóa alive
  // Output: khóa alive được tạo, instance vào danh sách và không bị coi là chết
  @Test
  void connectCreatesAliveKeyForNewInstance() {
    store.connect(s1, user, inst, at);

    assertThat(redis.hasKey("presence:instance:" + inst + ":alive")).isTrue();
    assertThat(store.deadInstances()).doesNotContain(inst);
  }

  // Kịch bản: 20 thread mở session khác nhau của cùng user
  // Input: 20 connect đồng thời
  // Output: đúng 1 lần trả true
  @Test
  void concurrentConnectsOfSameUserYieldExactlyOneTransition() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(20);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String sid = "c" + i + "-" + user;
      results.add(
          pool.submit(
              () -> {
                start.await();
                return store.connect(sid, user, inst, at);
              }));
    }
    start.countDown();
    int transitions = 0;
    for (Future<Boolean> f : results) {
      transitions += f.get() ? 1 : 0;
    }
    pool.shutdown();

    assertThat(transitions).isEqualTo(1);
  }

  // Kịch bản: instance chết có user chỉ ở đó và user còn session ở instance khác
  // Input: u1 chỉ có s1 ở inst; user có s2 ở inst và s3 ở instance khác; purge(inst)
  // Output: chỉ u1 về OFFLINE, user vẫn ONLINE, instance rời danh sách, nằm trong dead trước đó
  @Test
  void instancePurgeReturnsOnlyUsersWithNoOtherSession() {
    UUID u1 = UUID.randomUUID();
    String other = "o-" + UUID.randomUUID();
    store.connect(s1, u1, inst, at);
    store.connect(s2, user, inst, at);
    store.connect("s3-" + user, user, other, at);
    redis.delete("presence:instance:" + inst + ":alive");
    assertThat(store.deadInstances()).contains(inst);

    Set<UUID> offline = store.purge(inst);

    assertThat(offline).containsExactly(u1);
    assertThat(redis.opsForValue().get("presence:status:" + user)).isEqualTo("ONLINE");
    assertThat(redis.hasKey("presence:status:" + u1)).isFalse();
    assertThat(store.deadInstances()).doesNotContain(inst);
    assertThat(redis.hasKey("presence:instance:" + inst + ":sessions")).isFalse();
  }

  // Kịch bản: heartbeat làm instance sống lại giữa lúc phát hiện chết và lúc purge
  // Input: connect session (khóa alive còn), purge(inst) trực tiếp
  // Output: trả rỗng, session và status ONLINE còn nguyên
  @Test
  void purgeOfInstanceWithAliveKeyIsNoOp() {
    store.connect(s1, user, inst, at);

    assertThat(store.purge(inst)).isEmpty();

    assertThat(redis.opsForValue().get("presence:status:" + user)).isEqualTo("ONLINE");
    assertThat(redis.hasKey("presence:session:" + s1)).isTrue();
    assertThat(store.sessionsOf(inst, at.plusSeconds(1))).containsExactly(s1);
  }
}

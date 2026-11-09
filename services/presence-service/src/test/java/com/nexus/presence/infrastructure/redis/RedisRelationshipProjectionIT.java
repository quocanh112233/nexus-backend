package com.nexus.presence.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.testsupport.RedisContainer;
import java.time.Duration;
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

// Kiểm tra projection quan hệ trên Redis thật: key, TTL, SINTERCARD và nạp bù
class RedisRelationshipProjectionIT {

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;
  private static RedisRelationshipProjection projection;

  private final UUID user = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID s1 = UUID.randomUUID();
  private final UUID s2 = UUID.randomUUID();

  // Dựng client Lettuce tới container Redis
  @BeforeAll
  static void connect() {
    factory = new LettuceConnectionFactory(RedisContainer.host(), RedisContainer.port());
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    projection = new RedisRelationshipProjection(redis);
  }

  // Đóng kết nối Redis
  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  // Kịch bản: nạp tập server cho user
  // Input: load(user, {s1, s2})
  // Output: serversOf đủ hai server, membersOf(s1) chứa user, loaded TTL (23h, 24h], servers ~25h
  @Test
  void loadStoresSetsAndLoadedFlagWithTtl() {
    projection.load(user, Set.of(s1, s2), projection.revision(user));

    assertThat(projection.isLoaded(user)).isTrue();
    assertThat(projection.serversOf(user)).containsExactlyInAnyOrder(s1, s2);
    assertThat(projection.membersOf(s1)).containsExactly(user);
    Long ttl = redis.getExpire("presence:rel:" + user + ":loaded", TimeUnit.SECONDS);
    assertThat(ttl).isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
    assertThat(ttl).isGreaterThan(Duration.ofHours(23).toSeconds());
    Long serversTtl = redis.getExpire("presence:rel:" + user + ":servers", TimeUnit.SECONDS);
    assertThat(serversTtl)
        .isBetween(Duration.ofHours(24).toSeconds(), Duration.ofHours(25).toSeconds());
  }

  // Kịch bản: user lạ không có server nào bị nạp
  // Input: load(user, {})
  // Output: loaded có TTL tối đa 5 phút và không có key servers
  @Test
  void emptyLoadGetsShortLoadedTtl() {
    projection.load(user, Set.of(), projection.revision(user));

    assertThat(projection.isLoaded(user)).isTrue();
    Long ttl = redis.getExpire("presence:rel:" + user + ":loaded", TimeUnit.SECONDS);
    assertThat(ttl).isBetween(1L, Duration.ofMinutes(5).toSeconds());
    assertThat(redis.hasKey("presence:rel:" + user + ":servers")).isFalse();
  }

  // Kịch bản: hai user chỉ chung server khi hai tập giao nhau
  // Input: user có {s1}, other có {s2}, rồi other thêm s1
  // Output: shareServer false rồi true
  @Test
  void shareServerTrueOnlyWhenIntersect() {
    projection.load(user, Set.of(s1), projection.revision(user));
    projection.load(other, Set.of(s2), projection.revision(other));
    assertThat(projection.shareServer(user, other)).isFalse();

    projection.addServer(other, s1);

    assertThat(projection.shareServer(user, other)).isTrue();
  }

  // Kịch bản: cờ loaded hết hạn
  // Input: load rồi xóa key cờ loaded (tương đương hết hạn)
  // Output: isLoaded false, addServer chỉ ghi members chứ không ghi tập server của user
  @Test
  void expiredLoadedFlagMeansNotLoaded() {
    projection.load(user, Set.of(), projection.revision(user));
    redis.delete("presence:rel:" + user + ":loaded");

    projection.addServer(user, s1);

    assertThat(projection.isLoaded(user)).isFalse();
    assertThat(projection.membersOf(s1)).containsExactly(user);
    assertThat(redis.hasKey("presence:rel:" + user + ":servers")).isFalse();
  }

  // Kịch bản: mất hết key của user trong Redis
  // Input: nạp, xóa key servers và loaded, gọi ensure với loader giả trả {s1, s2}
  // Output: sau ensure user loaded và serversOf đúng {s1, s2}; ensure lần 2 không gọi loader nữa
  @Test
  void deletingKeysThenEnsureLoadedReloadsCorrectly() {
    int[] calls = {0};
    EnsureLoadedUseCase ensure =
        new EnsureLoadedUseCase(
            projection,
            id -> {
              calls[0]++;
              return Set.of(s1, s2);
            });
    projection.load(user, Set.of(s1), projection.revision(user));
    redis.delete(Set.of("presence:rel:" + user + ":servers", "presence:rel:" + user + ":loaded"));
    assertThat(projection.isLoaded(user)).isFalse();

    ensure.ensure(user);
    ensure.ensure(user);

    assertThat(projection.isLoaded(user)).isTrue();
    assertThat(projection.serversOf(user)).containsExactlyInAnyOrder(s1, s2);
    assertThat(calls[0]).isEqualTo(1);
  }

  // Kịch bản: xóa server
  // Input: load hai user cùng s1, removeServer một user rồi deleteServer(s1)
  // Output: membersOf(s1) mất dần rồi rỗng
  @Test
  void removeServerAndDeleteServerClearMembers() {
    projection.load(user, Set.of(s1), projection.revision(user));
    projection.load(other, Set.of(s1), projection.revision(other));

    projection.removeServer(user, s1);
    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(s1)).containsExactly(other);

    projection.deleteServer(s1);
    assertThat(projection.membersOf(s1)).isEmpty();
  }

  // Kịch bản: nạp với phiên bản cũ sau khi có event xử lý
  // Input: revision r, removeServer(user, s1), load(user, {s1}, r) rồi load với revision mới
  // Output: lần đầu false và không ghi key nào, lần sau true và cờ loaded có TTL
  @Test
  void staleRevisionWritesNothingAndFreshInstalls() {
    long stale = projection.revision(user);
    projection.removeServer(user, s1);

    assertThat(projection.load(user, Set.of(s1), stale)).isFalse();
    assertThat(redis.hasKey("presence:rel:" + user + ":loaded")).isFalse();
    assertThat(redis.hasKey("presence:rel:" + user + ":servers")).isFalse();
    assertThat(projection.membersOf(s1)).isEmpty();

    assertThat(projection.load(user, Set.of(s1), projection.revision(user))).isTrue();
    assertThat(projection.serversOf(user)).containsExactly(s1);
    Long ttl = redis.getExpire("presence:rel:" + user + ":loaded", TimeUnit.SECONDS);
    assertThat(ttl).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
  }

  // Kịch bản: add/remove cho user chưa loaded, rồi xóa server
  // Input: addServer, removeServer (user chưa nạp), deleteServer(s1)
  // Output: revision tăng sau mỗi lần, rev key có TTL ~25h, epoch tăng khi deleteServer
  @Test
  void revisionBumpsOnEventsAndEpochOnDeleteServer() {
    long r0 = projection.revision(user);
    projection.addServer(user, s1);
    long r1 = projection.revision(user);
    projection.removeServer(user, s1);
    long r2 = projection.revision(user);
    assertThat(r1).isGreaterThan(r0);
    assertThat(r2).isGreaterThan(r1);
    Long ttl = redis.getExpire("presence:rel:" + user + ":rev", TimeUnit.SECONDS);
    assertThat(ttl).isBetween(Duration.ofHours(24).toSeconds(), Duration.ofHours(25).toSeconds());

    projection.deleteServer(s2);

    assertThat(projection.revision(user)).isGreaterThan(r2);
    assertThat(projection.revision(other)).isPositive();
  }

  // Kịch bản: nhiều luồng nạp cùng revision trong lúc một event remove đến
  // Input: 8 luồng load(user, {s1}, r) và 1 luồng removeServer(user, s1) chạy đồng thời
  // Output: mọi thứ tự xen kẽ đều kết thúc với serversOf(user) và membersOf(s1) không còn user
  @Test
  void concurrentLoadsAndRemoveNeverLeaveStaleSnapshot() throws Exception {
    long r = projection.revision(user);
    ExecutorService pool = Executors.newFixedThreadPool(9);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> tasks = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      tasks.add(
          pool.submit(
              () -> {
                start.await();
                return projection.load(user, Set.of(s1), r);
              }));
    }
    tasks.add(
        pool.submit(
            () -> {
              start.await();
              projection.removeServer(user, s1);
              return null;
            }));
    start.countDown();
    for (Future<?> f : tasks) {
      f.get(5, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(projection.serversOf(user)).isEmpty();
    assertThat(projection.membersOf(s1)).doesNotContain(user);
  }
}

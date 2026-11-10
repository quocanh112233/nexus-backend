package com.nexus.messaging.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

// Kiểm tra cache-aside quyền kênh trên Redis thật, delegate là fake đếm số call
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class CachingChannelPermissionCheckerIT extends MessagingIntegrationTest {

  private static final Duration TTL = Duration.ofSeconds(30);

  @Autowired private StringRedisTemplate redis;

  @Value("${app.cache.permission-ttl}")
  private Duration configuredTtl;

  private final FakeChecker fake = new FakeChecker();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private CachingChannelPermissionChecker cache;
  private UserId user;
  private ChannelId channel;

  // Tạo user, kênh ngẫu nhiên và cache TTL 30 s bọc fake
  @BeforeEach
  void setUp() {
    user = new UserId(UUID.randomUUID());
    channel = new ChannelId(UUID.randomUUID());
    cache = new CachingChannelPermissionChecker(fake, redis, meters, TTL);
  }

  // Đọc giá trị counter theo tên
  private double count(String name) {
    return meters.get(name).counter().count();
  }

  // Kịch bản: gọi cùng user và kênh hai lần
  // Input: delegate trả VIEW_CHANNEL và SEND_MESSAGES, hai call check liên tiếp
  // Output: delegate chỉ bị gọi 1 lần, hai kết quả bằng nhau, hits=1, misses=1
  @Test
  void secondCallIsServedFromCacheWithoutDelegate() {
    fake.access =
        new ChannelAccess(
            UUID.randomUUID(),
            EnumSet.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES));

    ChannelAccess first = cache.check(user, channel);
    ChannelAccess second = cache.check(user, channel);

    assertThat(second).isEqualTo(first).isEqualTo(fake.access);
    assertThat(fake.calls.get()).isEqualTo(1);
    assertThat(count("nexus.permission.cache.hits")).isEqualTo(1.0);
    assertThat(count("nexus.permission.cache.misses")).isEqualTo(1.0);
  }

  // Kịch bản: user khác hoặc kênh khác không dùng chung entry
  // Input: check (u1,c1), (u2,c1), (u1,c2)
  // Output: delegate bị gọi 3 lần, tất cả đều miss
  @Test
  void differentUsersOrChannelsDoNotShareEntries() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    cache.check(user, channel);
    cache.check(new UserId(UUID.randomUUID()), channel);
    cache.check(user, new ChannelId(UUID.randomUUID()));

    assertThat(fake.calls.get()).isEqualTo(3);
    assertThat(count("nexus.permission.cache.misses")).isEqualTo(3.0);
  }

  // Kịch bản: entry hết hạn theo TTL
  // Input: cache TTL 1 s, call lần 1, chờ 1,2 s, call lần 2
  // Output: delegate được gọi 2 lần
  @Test
  void entryExpiresAfterTtl() throws Exception {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    cache = new CachingChannelPermissionChecker(fake, redis, meters, Duration.ofSeconds(1));

    cache.check(user, channel);
    Thread.sleep(1200);
    cache.check(user, channel);

    assertThat(fake.calls.get()).isEqualTo(2);
  }

  // Kịch bản: lỗi của delegate không bao giờ được cache
  // Input: delegate ném DependencyUnavailableException, rồi ChannelNotFoundException, rồi hồi phục
  // Output: hai call đầu ném lại đúng lỗi, call thứ 3 gọi delegate và thành công, đủ 3 call
  @Test
  void delegateExceptionsAreNotCached() {
    fake.failure = new DependencyUnavailableException("down", null);
    assertThatThrownBy(() -> cache.check(user, channel))
        .isInstanceOf(DependencyUnavailableException.class);
    fake.failure = new ChannelNotFoundException("channel not found");
    assertThatThrownBy(() -> cache.check(user, channel))
        .isInstanceOf(ChannelNotFoundException.class);
    fake.failure = null;
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThat(cache.check(user, channel)).isEqualTo(fake.access);
    assertThat(fake.calls.get()).isEqualTo(3);
  }

  // Kịch bản: Redis không kết nối được thì cache fail-open
  // Input: template mock ném RedisConnectionFailureException, delegate trả quyền
  // Output: trả đúng kết quả của delegate, không ném lỗi Redis, mỗi call đều gọi delegate
  @Test
  void redisDownFallsThroughToDelegate() {
    StringRedisTemplate broken = mock(StringRedisTemplate.class);
    when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
    cache = new CachingChannelPermissionChecker(fake, broken, meters, TTL);
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThat(cache.check(user, channel)).isEqualTo(fake.access);
    assertThat(cache.check(user, channel)).isEqualTo(fake.access);

    assertThat(fake.calls.get()).isEqualTo(2);
  }

  // Kịch bản: non-member (granted rỗng) cũng được cache
  // Input: delegate trả granted rỗng, hai call liên tiếp
  // Output: delegate chỉ bị gọi 1 lần, kết quả vẫn rỗng
  @Test
  void emptyGrantedIsCachedToo() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.noneOf(ChannelPermission.class));

    cache.check(user, channel);
    ChannelAccess second = cache.check(user, channel);

    assertThat(second.granted()).isEmpty();
    assertThat(fake.calls.get()).isEqualTo(1);
  }

  // Kịch bản: entry trong Redis bị hỏng thì coi như miss và được ghi đè
  // Input: đặt giá trị rác vào khóa perm:{channel}:{user}, hai call check
  // Output: call 1 gọi delegate và ghi đè giá trị đúng, call 2 trúng cache, delegate chỉ 1 call
  @Test
  void corruptEntryIsTreatedAsMissAndOverwritten() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    String key = "perm:" + channel.value() + ":" + user.value();
    redis.opsForValue().set(key, "not-a-valid-entry");

    cache.check(user, channel);
    cache.check(user, channel);

    assertThat(redis.opsForValue().get(key)).isEqualTo(fake.access.serverId() + "|1|0");
    assertThat(fake.calls.get()).isEqualTo(1);
  }

  // Kịch bản: bitmask ngoài khoảng hợp lệ coi như miss và được ghi đè
  // Input: entry "{serverId}|-1|0" rồi "{serverId}|64|0" (tối đa hợp lệ 63), mỗi lần một check
  // Output: mỗi lần delegate bị gọi 1 lần, entry bị ghi đè thành "|1|0", misses=2, hits=0
  @Test
  void outOfRangeBitmaskIsTreatedAsMissAndOverwritten() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    String key = "perm:" + channel.value() + ":" + user.value();
    int calls = 0;

    for (String bad : new String[] {"-1", "64"}) {
      redis.opsForValue().set(key, fake.access.serverId() + "|" + bad + "|0");

      assertThat(cache.check(user, channel)).isEqualTo(fake.access);

      assertThat(fake.calls.get()).isEqualTo(++calls);
      assertThat(redis.opsForValue().get(key)).isEqualTo(fake.access.serverId() + "|1|0");
    }
    assertThat(count("nexus.permission.cache.misses")).isEqualTo(2.0);
    assertThat(count("nexus.permission.cache.hits")).isZero();
  }

  // Kịch bản: Redis set ném lỗi runtime không phải DataAccessException thì vẫn fail-open
  // Input: template mock get trả null, set ném IllegalStateException, một call check
  // Output: trả đúng kết quả delegate, không ném lỗi, delegate 1 call, misses=1
  @Test
  void setFailureStillReturnsDelegateResult() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    StringRedisTemplate broken = mock(StringRedisTemplate.class);
    when(broken.opsForValue()).thenReturn(ops);
    doThrow(new IllegalStateException("boom"))
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));
    cache = new CachingChannelPermissionChecker(fake, broken, meters, TTL);
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThat(cache.check(user, channel)).isEqualTo(fake.access);

    assertThat(fake.calls.get()).isEqualTo(1);
    assertThat(count("nexus.permission.cache.misses")).isEqualTo(1.0);
  }

  // Kịch bản: bump phiên bản quyền của server vô hiệu entry đã cache
  // Input: check hai lần, INCR perm:ver:{serverId}, check lần ba
  // Output: sau hai call delegate 1 lần, sau INCR delegate 2 lần, hits=1, misses=2
  @Test
  void bumpInvalidatesCachedEntry() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    cache.check(user, channel);
    cache.check(user, channel);
    assertThat(fake.calls.get()).isEqualTo(1);
    redis.opsForValue().increment("perm:ver:" + fake.access.serverId());
    cache.check(user, channel);

    assertThat(fake.calls.get()).isEqualTo(2);
    assertThat(count("nexus.permission.cache.hits")).isEqualTo(1.0);
    assertThat(count("nexus.permission.cache.misses")).isEqualTo(2.0);
  }

  // Kịch bản: entry nhúng serverId, bitmask và phiên bản
  // Input: một call check rồi đọc giá trị khóa perm:{channel}:{user}
  // Output: giá trị khớp "^[0-9a-f-]{36}\|\d+\|\d+$" và bắt đầu bằng serverId
  @Test
  void entryEmbedsServerIdBitsAndVersion() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    cache.check(user, channel);

    assertThat(redis.opsForValue().get("perm:" + channel.value() + ":" + user.value()))
        .matches("^[0-9a-f-]{36}\\|\\d+\\|\\d+$")
        .startsWith(fake.access.serverId() + "|");
  }

  // Kịch bản: entry định dạng P4 (thiếu phiên bản) coi như miss
  // Input: ghi tay "{serverId}|3" vào khóa rồi một call check
  // Output: delegate được gọi 1 lần, entry bị ghi đè thành "|1|0"
  @Test
  void p4FormatEntryIsTreatedAsMiss() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    String key = "perm:" + channel.value() + ":" + user.value();
    redis.opsForValue().set(key, fake.access.serverId() + "|3");

    cache.check(user, channel);

    assertThat(fake.calls.get()).isEqualTo(1);
    assertThat(redis.opsForValue().get(key)).isEqualTo(fake.access.serverId() + "|1|0");
  }

  // Kịch bản: TTL cấu hình mặc định của app là 60 s
  // Input: cache dựng với app.cache.permission-ttl, một call check
  // Output: TTL còn lại của khóa trong khoảng (55, 60] giây
  @Test
  void ttlDefaultsTo60Seconds() {
    fake.access = new ChannelAccess(UUID.randomUUID(), EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    cache = new CachingChannelPermissionChecker(fake, redis, meters, configuredTtl);

    cache.check(user, channel);

    assertThat(redis.getExpire("perm:" + channel.value() + ":" + user.value()))
        .isLessThanOrEqualTo(60)
        .isGreaterThan(55);
  }

  // Delegate giả đếm call, trả access hoặc ném failure đã cấu hình
  private static final class FakeChecker implements ChannelPermissionChecker {

    final AtomicInteger calls = new AtomicInteger();
    volatile ChannelAccess access;
    volatile RuntimeException failure;

    // Đếm call rồi trả access hoặc ném failure
    @Override
    public ChannelAccess check(UserId userId, ChannelId channelId) {
      calls.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      return access;
    }
  }
}

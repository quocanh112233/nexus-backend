package com.nexus.messaging.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.infrastructure.persistence.channel.TombstoneChannelPermissionChecker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

// Kiểm tra công tắc app.cache.permission-enabled của bean channelPermissionChecker
class PermissionCacheToggleTest {

  private static final UserId USER = new UserId(UUID.randomUUID());
  private static final ChannelId CHANNEL = new ChannelId(UUID.randomUUID());

  private final TombstoneChannelPermissionChecker serverChecker =
      mock(TombstoneChannelPermissionChecker.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  // Dựng bean với repository mock (kênh không phải DM) và cờ cache cho trước
  private ChannelPermissionChecker bean(boolean cacheEnabled) {
    return new CommunityChannelConfig()
        .channelPermissionChecker(
            serverChecker,
            mock(ChannelRefRepository.class),
            mock(DmChannelRepository.class),
            mock(FriendshipRepository.class),
            redis,
            new SimpleMeterRegistry(),
            Duration.ofSeconds(60),
            cacheEnabled);
  }

  // Kịch bản: tắt cache quyền thì mỗi lần kiểm tra kênh server đều hỏi delegate và không chạm Redis
  // Input: cacheEnabled=false, kênh SERVER, gọi check hai lần cùng user và kênh
  // Output: serverChecker.check được gọi 2 lần, redis không có tương tác nào
  @Test
  void tatCacheThiLuonHoiDelegateVaKhongChamRedis() {
    when(serverChecker.check(any(), any()))
        .thenReturn(new ChannelAccess(UUID.randomUUID(), Set.of(ChannelPermission.VIEW_CHANNEL)));
    ChannelPermissionChecker checker = bean(false);

    checker.check(USER, CHANNEL);
    checker.check(USER, CHANNEL);

    verify(serverChecker, times(2)).check(USER, CHANNEL);
    verifyNoInteractions(redis);
  }

  // Kịch bản: tắt cache quyền không nới quyền của user bị từ chối (Review Focus 4)
  // Input: cacheEnabled=false, serverChecker trả ChannelAccess không có quyền nào
  // Output: kết quả check không có quyền nào
  @Test
  void tatCacheVanTuChoiUserKhongCoQuyen() {
    when(serverChecker.check(any(), any()))
        .thenReturn(new ChannelAccess(UUID.randomUUID(), Set.of()));

    ChannelAccess access = bean(false).check(USER, CHANNEL);

    assertThat(access.granted()).isEmpty();
  }

  // Kịch bản: bật cache quyền (mặc định) thì lần kiểm tra đầu có đọc Redis
  // Input: cacheEnabled=true, kênh SERVER, ValueOperations được stub (get trả null), gọi check một
  // lần
  // Output: ValueOperations.get được gọi với khóa perm:{channelId}:{userId} rồi mới hỏi delegate
  @Test
  @SuppressWarnings("unchecked")
  void batCacheThiDocRedis() {
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(serverChecker.check(any(), any()))
        .thenReturn(new ChannelAccess(UUID.randomUUID(), Set.of(ChannelPermission.VIEW_CHANNEL)));

    bean(true).check(USER, CHANNEL);

    verify(ops).get("perm:" + CHANNEL.value() + ":" + USER.value());
    verify(serverChecker).check(USER, CHANNEL);
  }
}

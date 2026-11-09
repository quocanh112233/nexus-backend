package com.nexus.realtime.application.typing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Unit test TypingService: điều kiện subscribe kênh và throttle 3 s
class TypingServiceTest {

  private int scans;
  private final SubscriptionRegistry registry =
      new SubscriptionRegistry() {
        // Đếm số lần quét registry theo user
        @Override
        public List<SessionSubscription> byUser(UUID userId) {
          scans++;
          return super.byUser(userId);
        }
      };
  private final Tick clock = new Tick();
  private final TypingService service = new TypingService(registry, clock);
  private final UUID user = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Kịch bản: user đã subscribe kênh và gõ lần đầu
  // Input: accept(user, channel)
  // Output: true
  @Test
  void firstTypingAccepted() {
    subscribe(channel);
    assertThat(service.accept(user, channel)).isTrue();
  }

  // Kịch bản: gõ lần hai chưa đủ 3 giây
  // Input: accept hai lần, cách nhau 2999 ms
  // Output: lần đầu true, lần hai false
  @Test
  void secondWithinThreeSecondsDropped() {
    subscribe(channel);
    assertThat(service.accept(user, channel)).isTrue();
    clock.advance(Duration.ofMillis(2999));
    assertThat(service.accept(user, channel)).isFalse();
  }

  // Kịch bản: gõ lại sau đúng 3 giây
  // Input: accept hai lần, cách nhau 3000 ms
  // Output: cả hai true
  @Test
  void afterThreeSecondsAccepted() {
    subscribe(channel);
    assertThat(service.accept(user, channel)).isTrue();
    clock.advance(Duration.ofSeconds(3));
    assertThat(service.accept(user, channel)).isTrue();
  }

  // Kịch bản: user chưa subscribe kênh nên chưa chứng minh được quyền xem
  // Input: accept(user, channel) khi registry chỉ có subscription kênh khác
  // Output: false
  @Test
  void withoutSubscriptionRejected() {
    subscribe(UUID.randomUUID());
    assertThat(service.accept(user, channel)).isFalse();
  }

  // Kịch bản: throttle tách theo từng kênh
  // Input: user subscribe hai kênh, gõ kênh 1 rồi ngay kênh 2 rồi lại kênh 1
  // Output: true, true, false
  @Test
  void differentChannelsIndependent() {
    UUID other = UUID.randomUUID();
    subscribe(channel);
    subscribe(other);
    assertThat(service.accept(user, channel)).isTrue();
    assertThat(service.accept(user, other)).isTrue();
    assertThat(service.accept(user, channel)).isFalse();
  }

  // Kịch bản: gõ dồn dập trong cửa sổ throttle không làm tốn quét registry
  // Input: accept bốn lần liên tiếp trong 3 giây
  // Output: chỉ quét registry đúng một lần, lần đầu true và ba lần sau false
  @Test
  void spamWithinWindowDoesNotScanRegistry() {
    subscribe(channel);
    assertThat(service.accept(user, channel)).isTrue();
    assertThat(service.accept(user, channel)).isFalse();
    assertThat(service.accept(user, channel)).isFalse();
    assertThat(service.accept(user, channel)).isFalse();
    assertThat(scans).isEqualTo(1);
  }

  // Kịch bản: đồng hồ bị chỉnh lùi sau lần gõ đầu
  // Input: accept, lùi đồng hồ 1 giây, accept lại
  // Output: lần hai vẫn true, user không bị im vì đồng hồ lùi
  @Test
  void negativeClockStepDoesNotSilenceUser() {
    subscribe(channel);
    assertThat(service.accept(user, channel)).isTrue();
    clock.advance(Duration.ofSeconds(-1));
    assertThat(service.accept(user, channel)).isTrue();
  }

  // Ghi subscription kênh của user vào registry
  private void subscribe(UUID channelId) {
    registry.add(
        new SessionSubscription(
            "s", user, "sub-" + channelId, "/topic/channels/" + channelId, null));
  }

  // Đồng hồ giả chỉ chạy khi test gọi advance
  private static final class Tick extends Clock {

    private Instant now = Instant.parse("2026-11-08T00:00:00Z");

    // Cho đồng hồ chạy tới
    void advance(Duration d) {
      now = now.plus(d);
    }

    // Múi giờ cố định UTC
    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    // Không đổi múi giờ
    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    // Thời điểm hiện tại của đồng hồ giả
    @Override
    public Instant instant() {
      return now;
    }
  }
}

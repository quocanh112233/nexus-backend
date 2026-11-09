package com.nexus.realtime.application.typing;

import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// Quyết định có nhận sự kiện gõ không: phải đang subscribe kênh và cách lần trước tối thiểu 3 s
@Component
public class TypingService {

  private static final long WINDOW_MILLIS = 3_000;
  private static final int CLEANUP_THRESHOLD = 1_024;

  private final SubscriptionRegistry registry;
  private final Clock clock;
  private final Map<Key, Long> lastTyped = new ConcurrentHashMap<>();

  // Nhận sổ subscription và đồng hồ
  public TypingService(SubscriptionRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
  }

  // Nhận khi user đang subscribe kênh và chưa gõ trong 3 s gần nhất, ngược lại bỏ lặng lẽ
  public boolean accept(UUID userId, UUID channelId) {
    long now = clock.millis();
    Key key = new Key(userId, channelId);
    if (throttled(lastTyped.get(key), now)) {
      return false;
    }
    String destination = "/topic/channels/" + channelId;
    if (registry.byUser(userId).stream().noneMatch(s -> s.destination().equals(destination))) {
      return false;
    }
    if (lastTyped.size() >= CLEANUP_THRESHOLD) {
      lastTyped.values().removeIf(t -> !throttled(t, now));
    }
    boolean[] accepted = {false};
    lastTyped.compute(
        key,
        (k, last) -> {
          if (throttled(last, now)) {
            return last;
          }
          accepted[0] = true;
          return now;
        });
    return accepted[0];
  }

  // Còn trong cửa sổ 3 s, đồng hồ lùi thì coi như đã hết hạn
  private static boolean throttled(Long last, long now) {
    return last != null && now - last >= 0 && now - last < WINDOW_MILLIS;
  }

  // Khóa throttle theo cặp (user, kênh)
  private record Key(UUID userId, UUID channelId) {}
}

package com.nexus.realtime.infrastructure.amqp;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Theo dõi session đang mở của instance và báo Presence: connect, disconnect, heartbeat, shutdown
@Component
public class PresenceReporter {

  private static final Logger LOG = LoggerFactory.getLogger(PresenceReporter.class);
  private static final long TOMBSTONE_MILLIS = 60_000;

  private final RealtimeEventPublisher publisher;
  private final InstanceId instanceId;
  private final Clock clock;
  private final Map<String, UUID> open = new ConcurrentHashMap<>();
  private final Map<String, Long> closedAt = new ConcurrentHashMap<>();

  // Nhận publisher, định danh instance và đồng hồ
  public PresenceReporter(RealtimeEventPublisher publisher, InstanceId instanceId, Clock clock) {
    this.publisher = publisher;
    this.instanceId = instanceId;
    this.clock = clock;
  }

  // Ghi session mới mở rồi kiểm dấu đã đóng (sau put, tránh race với closed), mở muộn thì bỏ
  public void opened(String sessionId, UUID userId) {
    expireTombstones();
    open.put(sessionId, userId);
    if (closedAt.containsKey(sessionId)) {
      open.remove(sessionId);
      return;
    }
    publisher.publishSessionConnected(sessionId, userId, instanceId.value(), clock.instant());
  }

  // Phát SessionDisconnected đúng một lần, ghi dấu đã đóng để opened đến muộn không tạo session ma
  public void closed(String sessionId) {
    expireTombstones();
    closedAt.put(sessionId, clock.millis());
    disconnect(sessionId);
  }

  // Phát heartbeat định kỳ kèm các session đang mở
  @Scheduled(
      fixedRateString = "${app.presence.heartbeat-interval:30s}",
      initialDelayString = "${app.presence.heartbeat-interval:30s}")
  void heartbeat() {
    publisher.publishHeartbeat(instanceId.value(), Map.copyOf(open), clock.instant());
  }

  // Khi dừng graceful, báo disconnect cho mọi session còn lại, dừng ở lần lỗi đầu để kịp 30 s
  @PreDestroy
  void shutdown() {
    List<String> ids = List.copyOf(open.keySet());
    for (int i = 0; i < ids.size(); i++) {
      if (!disconnect(ids.get(i))) {
        LOG.warn(
            "Broker lỗi khi dừng, bỏ qua SessionDisconnected của {} session", ids.size() - i - 1);
        return;
      }
    }
  }

  // Gỡ session khỏi tập đang mở và phát SessionDisconnected, trả false nếu gửi lỗi
  private boolean disconnect(String sessionId) {
    UUID userId = open.remove(sessionId);
    return userId == null
        || publisher.publishSessionDisconnected(sessionId, userId, instanceId.value());
  }

  // Xóa dấu đã đóng quá 60 s
  private void expireTombstones() {
    long now = clock.millis();
    closedAt.values().removeIf(t -> now - t >= TOMBSTONE_MILLIS);
  }
}

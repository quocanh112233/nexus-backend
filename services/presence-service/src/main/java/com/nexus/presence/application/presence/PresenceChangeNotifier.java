package com.nexus.presence.application.presence;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.domain.presence.PresenceStatus;
import com.nexus.presence.domain.relation.RelationshipProjection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Dựng PresenceChange với serverIds từ projection rồi phát, Community lỗi vẫn phát
@Component
public class PresenceChangeNotifier {

  private static final Logger LOG = LoggerFactory.getLogger(PresenceChangeNotifier.class);

  private final EnsureLoadedUseCase ensure;
  private final RelationshipProjection projection;
  private final PresenceChangePublisher publisher;
  private final Clock clock;

  // Nhận use case nạp quan hệ, projection, cổng phát và đồng hồ
  public PresenceChangeNotifier(
      EnsureLoadedUseCase ensure,
      RelationshipProjection projection,
      PresenceChangePublisher publisher,
      Clock clock) {
    this.ensure = ensure;
    this.projection = projection;
    this.publisher = publisher;
    this.clock = clock;
  }

  // Nạp bù quan hệ rồi phát; nạp lỗi dùng server đã có, đọc projection lỗi dùng danh sách rỗng
  public void changed(UUID userId, PresenceStatus status) {
    List<UUID> servers = List.of();
    try {
      ensure.ensure(userId);
    } catch (RuntimeException e) {
      LOG.warn("Không nạp được quan hệ của {}, phát với server đã có: {}", userId, e.toString());
    }
    try {
      servers = List.copyOf(projection.serversOf(userId));
    } catch (RuntimeException e) {
      LOG.warn("Không đọc được server của {}, phát với danh sách rỗng: {}", userId, e.toString());
    }
    publisher.publish(
        new PresenceChange(userId, status.name(), clock.instant(), servers, List.of()));
  }
}

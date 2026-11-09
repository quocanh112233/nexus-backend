package com.nexus.realtime.application.typing;

import java.time.Instant;
import java.util.UUID;

// Cổng phát sự kiện đang gõ ra các instance gateway
public interface TypingPublisher {

  // Phát TypingStarted của user trong kênh
  void publishTyping(UUID channelId, UUID userId, Instant startedAt);
}

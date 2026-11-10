package com.nexus.messaging.domain.social;

import java.time.Instant;

// Quan hệ bạn bè giữa hai người, bất biến
public record Friendship(UserPair pair, Instant createdAt) {

  // Tạo quan hệ bạn bè mới tại thời điểm now
  public static Friendship create(UserPair pair, Instant now) {
    return new Friendship(pair, now);
  }
}

package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.social.FriendRequest;
import java.time.Instant;
import java.util.UUID;

// Góc nhìn lời mời kết bạn cho tầng giao tiếp, status là tên enum
public record FriendRequestView(
    UUID id, UUID requesterId, UUID addresseeId, String status, Instant createdAt) {

  // Chép từ aggregate
  static FriendRequestView from(FriendRequest r) {
    return new FriendRequestView(
        r.id().value(),
        r.requester().value(),
        r.addressee().value(),
        r.status().name(),
        r.createdAt());
  }
}

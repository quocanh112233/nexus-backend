package com.nexus.messaging.interfaces.rest;

import java.util.List;
import java.util.UUID;

// Phản hồi danh sách bạn bè chỉ gồm id, hồ sơ do frontend tra ở Identity
record FriendListResponse(List<Friend> items) {

  // Một người bạn
  record Friend(UUID userId) {}

  // Dựng từ danh sách id
  static FriendListResponse of(List<UUID> ids) {
    return new FriendListResponse(ids.stream().map(Friend::new).toList());
  }
}

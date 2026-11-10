package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.social.DmView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phản hồi một kênh DM: id kênh, người kia và thời điểm tạo
record DmResponse(UUID channelId, UUID friendId, Instant createdAt) {

  // Phản hồi danh sách DM
  record Items(List<DmResponse> items) {}

  // Dựng từ view ứng dụng
  static DmResponse from(DmView view) {
    return new DmResponse(view.channelId(), view.friendId(), view.createdAt());
  }
}

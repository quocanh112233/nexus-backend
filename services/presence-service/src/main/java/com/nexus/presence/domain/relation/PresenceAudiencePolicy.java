package com.nexus.presence.domain.relation;

import java.util.UUID;

// Quy tắc riêng tư NFR-018: chỉ chính mình hoặc người chung server thấy trạng thái (P7 thêm bạn bè)
public final class PresenceAudiencePolicy {

  // Lớp tiện ích, không khởi tạo
  private PresenceAudiencePolicy() {}

  // Viewer thấy target khi là cùng người hoặc chung ít nhất một server
  public static boolean canSee(UUID viewer, UUID target, RelationshipProjection projection) {
    return viewer.equals(target) || projection.shareServer(viewer, target);
  }
}

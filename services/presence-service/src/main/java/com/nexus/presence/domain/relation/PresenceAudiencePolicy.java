package com.nexus.presence.domain.relation;

import java.util.UUID;

// Quy tắc riêng tư NFR-018: chỉ chính mình, bạn bè hoặc người chung server thấy trạng thái
public final class PresenceAudiencePolicy {

  // Lớp tiện ích, không khởi tạo
  private PresenceAudiencePolicy() {}

  // Viewer thấy target khi là cùng người, là bạn hoặc chung ít nhất một server
  public static boolean canSee(UUID viewer, UUID target, RelationshipProjection projection) {
    return viewer.equals(target)
        || projection.isFriend(viewer, target)
        || projection.shareServer(viewer, target);
  }
}

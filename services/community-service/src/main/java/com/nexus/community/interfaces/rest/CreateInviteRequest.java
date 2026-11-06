package com.nexus.community.interfaces.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// Body tạo lời mời, mọi trường tùy chọn và kiểm tra khoảng ở biên REST
public record CreateInviteRequest(
    @Min(0) @Max(1000) Integer maxUses, @Min(60) @Max(2592000) Long expiresInSeconds) {

  private static final int DEFAULT_MAX_USES = 0;
  private static final long DEFAULT_EXPIRES_IN_SECONDS = 604800;

  // Số lượt tối đa, vắng thì 0 là không giới hạn
  int maxUsesOrDefault() {
    return maxUses == null ? DEFAULT_MAX_USES : maxUses;
  }

  // Số giây tới khi hết hạn, vắng thì 7 ngày
  long expiresInSecondsOrDefault() {
    return expiresInSeconds == null ? DEFAULT_EXPIRES_IN_SECONDS : expiresInSeconds;
  }
}

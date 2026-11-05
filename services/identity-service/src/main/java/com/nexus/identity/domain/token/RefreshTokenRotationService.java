package com.nexus.identity.domain.token;

import java.time.Instant;

// Domain service quyết định số phận của refresh token được xuất trình
public final class RefreshTokenRotationService {

  // Đã thu hồi là Reuse, chưa thu hồi nhưng hết hạn là Expired, còn lại là Accept
  public RotationDecision decide(RefreshToken presented, Instant now) {
    if (presented.isRevoked()) {
      return new RotationDecision.Reuse(presented.familyId());
    }
    if (presented.isExpired(now)) {
      return new RotationDecision.Expired();
    }
    return new RotationDecision.Accept();
  }
}

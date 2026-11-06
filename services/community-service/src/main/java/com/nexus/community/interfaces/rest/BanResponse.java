package com.nexus.community.interfaces.rest;

import com.nexus.community.application.ban.BanView;
import java.time.Instant;
import java.util.UUID;

// Phản hồi một lệnh ban
public record BanResponse(UUID userId, UUID bannedBy, String reason, Instant createdAt) {

  // Dựng phản hồi từ góc nhìn ban
  static BanResponse from(BanView v) {
    return new BanResponse(v.userId(), v.bannedBy(), v.reason(), v.createdAt());
  }
}

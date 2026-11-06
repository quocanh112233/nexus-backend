package com.nexus.community.application.ban;

import com.nexus.community.domain.ban.ServerBan;
import java.time.Instant;
import java.util.UUID;

// Góc nhìn một lệnh ban
public record BanView(UUID userId, UUID bannedBy, String reason, Instant createdAt) {

  // Dựng góc nhìn từ aggregate ban
  static BanView from(ServerBan ban) {
    return new BanView(ban.userId().value(), ban.bannedBy().value(), ban.reason(), ban.createdAt());
  }
}

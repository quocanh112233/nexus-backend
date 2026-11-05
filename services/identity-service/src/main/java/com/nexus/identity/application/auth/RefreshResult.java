package com.nexus.identity.application.auth;

import com.nexus.identity.application.port.AccessToken;
import java.time.Instant;

// Kết quả refresh: access token mới và refresh token thô mới kèm hạn
public record RefreshResult(
    AccessToken accessToken, String refreshTokenRaw, Instant refreshExpiresAt) {

  // Che token để không lộ qua log
  @Override
  public String toString() {
    return "RefreshResult[refreshExpiresAt=" + refreshExpiresAt + "]";
  }
}

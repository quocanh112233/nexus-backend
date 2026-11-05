package com.nexus.identity.application.auth;

import com.nexus.identity.application.port.AccessToken;
import java.time.Instant;

// Kết quả đăng nhập thành công: access token và refresh token thô kèm hạn
public record LoginResult(
    AccessToken accessToken, String refreshTokenRaw, Instant refreshExpiresAt) {

  // Che token để không lộ qua log
  @Override
  public String toString() {
    return "LoginResult[refreshExpiresAt=" + refreshExpiresAt + "]";
  }
}

package com.nexus.identity.application.port;

import java.time.Instant;

// Access token đã ký kèm thời điểm hết hạn
public record AccessToken(String value, Instant expiresAt) {

  // Che token để không lộ qua log
  @Override
  public String toString() {
    return "AccessToken[expiresAt=" + expiresAt + "]";
  }
}

package com.nexus.identity.application.port;

import com.nexus.identity.domain.token.TokenHash;

// Refresh token vừa sinh: chuỗi thô gửi cho client và chuỗi băm để lưu DB
public record GeneratedRefreshToken(String raw, TokenHash hash) {

  // Che token thô và chuỗi băm để không lộ qua log
  @Override
  public String toString() {
    return "GeneratedRefreshToken[raw=****]";
  }
}

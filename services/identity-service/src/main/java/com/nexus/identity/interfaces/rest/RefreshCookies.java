package com.nexus.identity.interfaces.rest;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// Dựng cookie nexus_rt chứa refresh token: HttpOnly, Secure, SameSite=Strict, path /api/v1/auth
@Component
class RefreshCookies {

  static final String NAME = "nexus_rt";

  private final boolean secure;
  private final Duration ttl;

  // Nhận cờ Secure và thời gian sống refresh token từ cấu hình
  RefreshCookies(
      @Value("${app.cookie.secure}") boolean secure, @Value("${app.refresh.ttl}") Duration ttl) {
    this.secure = secure;
    this.ttl = ttl;
  }

  // Cookie mang refresh token thô, Max-Age bằng TTL refresh
  String issue(String raw) {
    return build(raw, ttl).toString();
  }

  // Cookie rỗng Max-Age=0 để trình duyệt xóa nexus_rt
  String clear() {
    return build("", Duration.ZERO).toString();
  }

  // Dựng ResponseCookie với các thuộc tính cố định
  private ResponseCookie build(String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(maxAge)
        .build();
  }
}

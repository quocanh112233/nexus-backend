package com.nexus.identity.application.port;

// Cổng sinh refresh token ngẫu nhiên
public interface RefreshTokenGenerator {

  // Sinh token mới kèm chuỗi băm
  GeneratedRefreshToken generate();
}

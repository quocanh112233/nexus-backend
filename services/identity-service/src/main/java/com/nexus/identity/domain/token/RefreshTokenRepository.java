package com.nexus.identity.domain.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Kho lưu refresh token, các thao tác thu hồi chạy trong transaction của nơi gọi
public interface RefreshTokenRepository {

  // Lưu token mới và trả bản đã lưu
  RefreshToken save(RefreshToken token);

  // Tìm token theo chuỗi băm
  Optional<RefreshToken> findByHash(TokenHash hash);

  // Thu hồi nguyên tử bằng một câu UPDATE có điều kiện, true khi đúng một dòng bị đổi
  boolean revokeIfActive(UUID id, UUID replacedById, Instant now);

  // Thu hồi mọi token chưa thu hồi của family và trả số dòng đã đổi
  int revokeFamily(UUID familyId, Instant now);
}

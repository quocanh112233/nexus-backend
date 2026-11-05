package com.nexus.identity.infrastructure.persistence.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng refresh_tokens, các UPDATE thu hồi chạy trong transaction của nơi gọi
interface RefreshTokenDao extends JpaRepository<RefreshTokenJpaEntity, UUID> {

  // Tìm theo chuỗi băm token
  Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

  // Thu hồi một token nếu còn hoạt động, trả số dòng bị đổi (0 hoặc 1)
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RefreshTokenJpaEntity t set t.revokedAt = :now, t.replacedById = :replacedById"
          + " where t.id = :id and t.revokedAt is null")
  int revokeIfActive(
      @Param("id") UUID id, @Param("replacedById") UUID replacedById, @Param("now") Instant now);

  // Thu hồi mọi token chưa thu hồi của family, trả số dòng bị đổi
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RefreshTokenJpaEntity t set t.revokedAt = :now"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}

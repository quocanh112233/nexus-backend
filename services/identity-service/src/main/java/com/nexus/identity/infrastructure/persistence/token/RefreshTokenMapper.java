package com.nexus.identity.infrastructure.persistence.token;

import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.UserId;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa RefreshToken và RefreshTokenJpaEntity
@Component
final class RefreshTokenMapper {

  // Dựng lại token từ entity qua RefreshToken.rehydrate
  RefreshToken toDomain(RefreshTokenJpaEntity e) {
    return RefreshToken.rehydrate(
        e.getId(),
        new UserId(e.getUserId()),
        e.getFamilyId(),
        new TokenHash(e.getTokenHash()),
        e.getExpiresAt(),
        e.getRevokedAt(),
        e.getReplacedById(),
        e.getCreatedAt(),
        e.getUserAgent(),
        e.getIp());
  }

  // Dựng entity từ token
  RefreshTokenJpaEntity toJpa(RefreshToken t) {
    RefreshTokenJpaEntity e = new RefreshTokenJpaEntity();
    e.setId(t.id());
    e.setUserId(t.userId().value());
    e.setTokenHash(t.tokenHash().value());
    e.setFamilyId(t.familyId());
    e.setExpiresAt(t.expiresAt());
    e.setRevokedAt(t.revokedAt());
    e.setReplacedById(t.replacedById());
    e.setCreatedAt(t.createdAt());
    e.setUserAgent(t.userAgent());
    e.setIp(t.ip());
    return e;
  }
}

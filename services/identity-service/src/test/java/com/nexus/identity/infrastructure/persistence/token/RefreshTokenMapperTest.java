package com.nexus.identity.infrastructure.persistence.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra RefreshTokenMapper chuyển đổi hai chiều không mất field
class RefreshTokenMapperTest {

  private final RefreshTokenMapper mapper = new RefreshTokenMapper();

  // Kịch bản: token đã thu hồi đi vòng qua entity JPA rồi về domain
  // Input: token rehydrate với revokedAt, replacedById, userAgent và ip có giá trị
  // Output: toDomain(toJpa(token)) bằng token từng field
  @Test
  void roundTripKeepsAllFields() {
    RefreshToken token =
        RefreshToken.rehydrate(
            UUID.randomUUID(),
            UserId.newId(),
            UUID.randomUUID(),
            TokenHash.ofRaw("x"),
            Instant.parse("2026-02-01T00:00:00Z"),
            Instant.parse("2026-01-05T00:00:00Z"),
            UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"),
            "agent",
            "10.0.0.1");

    assertThat(mapper.toDomain(mapper.toJpa(token))).usingRecursiveComparison().isEqualTo(token);
  }
}

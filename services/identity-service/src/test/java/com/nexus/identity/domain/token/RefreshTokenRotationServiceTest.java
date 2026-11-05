package com.nexus.identity.domain.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.domain.user.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra RefreshTokenRotationService quyết định chấp nhận, tái sử dụng hay hết hạn
class RefreshTokenRotationServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final RefreshTokenRotationService service = new RefreshTokenRotationService();

  // Kịch bản: token còn hạn và chưa bị thu hồi
  // Input: token phát lúc NOW ttl 30 ngày, kiểm tra tại NOW cộng 1 ngày
  // Output: quyết định là Accept
  @Test
  void activeTokenIsAccepted() {
    RefreshToken token = issued();

    assertThat(service.decide(token, NOW.plus(Duration.ofDays(1))))
        .isInstanceOf(RotationDecision.Accept.class);
  }

  // Kịch bản: token đã bị thu hồi mà vẫn được xuất trình lại
  // Input: token đã rehydrate với revokedAt khác null
  // Output: quyết định là Reuse mang familyId của token
  @Test
  void revokedTokenIsReuse() {
    UUID familyId = UUID.randomUUID();
    RefreshToken token =
        RefreshToken.rehydrate(
            UUID.randomUUID(),
            UserId.newId(),
            familyId,
            TokenHash.ofRaw("x"),
            NOW.plus(Duration.ofDays(30)),
            NOW.plusSeconds(5),
            UUID.randomUUID(),
            NOW,
            null,
            null);

    assertThat(service.decide(token, NOW.plusSeconds(10)))
        .isEqualTo(new RotationDecision.Reuse(familyId));
  }

  // Kịch bản: token chưa thu hồi nhưng đã quá hạn
  // Input: token phát lúc NOW ttl 30 ngày, kiểm tra tại NOW cộng 31 ngày
  // Output: quyết định là Expired
  @Test
  void expiredButNotRevokedIsExpired() {
    RefreshToken token = issued();

    assertThat(service.decide(token, NOW.plus(Duration.ofDays(31))))
        .isEqualTo(new RotationDecision.Expired());
  }

  // Dựng token mới phát lúc NOW với ttl 30 ngày
  private static RefreshToken issued() {
    return RefreshToken.issue(
        UUID.randomUUID(),
        UserId.newId(),
        UUID.randomUUID(),
        TokenHash.ofRaw("x"),
        NOW,
        Duration.ofDays(30),
        "agent",
        "127.0.0.1");
  }
}

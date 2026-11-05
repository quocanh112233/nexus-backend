package com.nexus.identity.application.auth;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.identity.application.port.AccessToken;
import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.application.port.RefreshTokenGenerator;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.RefreshTokenRotationService;
import com.nexus.identity.domain.token.RotationDecision;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case refresh: xoay vòng token cùng family, dùng lại hoặc thua cuộc đua thì thu hồi cả family
@Service
public class RefreshTokenUseCase {

  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final TokenIssuer tokenIssuer;
  private final RefreshTokenGenerator generator;
  private final RevokeRefreshTokenFamilyUseCase revokeFamily;
  private final Clock clock;
  private final Duration refreshTtl;
  private final RefreshTokenRotationService rotation = new RefreshTokenRotationService();

  // Nhận các cổng, use case thu hồi family (bean riêng để REQUIRES_NEW có hiệu lực) và TTL
  public RefreshTokenUseCase(
      RefreshTokenRepository refreshTokens,
      UserRepository users,
      TokenIssuer tokenIssuer,
      RefreshTokenGenerator generator,
      RevokeRefreshTokenFamilyUseCase revokeFamily,
      Clock clock,
      @Value("${app.refresh.ttl}") Duration refreshTtl) {
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.tokenIssuer = tokenIssuer;
    this.generator = generator;
    this.revokeFamily = revokeFamily;
    this.clock = clock;
    this.refreshTtl = refreshTtl;
  }

  // Đổi refresh token thô lấy cặp token mới, lỗi chung khi lạ/hết hạn, báo dùng lại khi reuse
  @Transactional
  public RefreshResult execute(String rawToken, String userAgent, String ip) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new InvalidRefreshTokenException();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    RefreshToken presented =
        refreshTokens
            .findByHash(TokenHash.ofRaw(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
    switch (rotation.decide(presented, now)) {
      case RotationDecision.Reuse reuse -> throw reused(reuse.familyId());
      case RotationDecision.Expired expired -> throw new InvalidRefreshTokenException();
      case RotationDecision.Accept accept -> {}
    }
    User user = users.findById(presented.userId()).orElseThrow(InvalidRefreshTokenException::new);
    GeneratedRefreshToken generated = generator.generate();
    RefreshToken fresh =
        refreshTokens.save(
            RefreshToken.issue(
                UuidCreator.getTimeOrderedEpoch(),
                user.id(),
                presented.familyId(),
                generated.hash(),
                now,
                refreshTtl,
                userAgent,
                ip));
    if (!refreshTokens.revokeIfActive(presented.id(), fresh.id(), now)) {
      throw reused(presented.familyId());
    }
    AccessToken accessToken = tokenIssuer.issue(user);
    return new RefreshResult(accessToken, generated.raw(), fresh.expiresAt());
  }

  // Thu hồi family trong transaction riêng đã commit rồi trả lỗi dùng lại để nơi gọi ném
  private RefreshTokenReusedException reused(UUID familyId) {
    revokeFamily.execute(familyId);
    return new RefreshTokenReusedException();
  }
}

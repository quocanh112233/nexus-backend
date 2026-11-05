package com.nexus.identity.application.auth;

import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đăng xuất: thu hồi refresh token hiện tại nếu có, im lặng trong mọi trường hợp khác
@Service
public class LogoutUseCase {

  private final RefreshTokenRepository refreshTokens;
  private final Clock clock;

  // Nhận kho refresh token và đồng hồ qua constructor
  public LogoutUseCase(RefreshTokenRepository refreshTokens, Clock clock) {
    this.refreshTokens = refreshTokens;
    this.clock = clock;
  }

  // Thu hồi token thô nếu tồn tại và còn hoạt động, null hoặc rỗng thì bỏ qua
  @Transactional
  public void execute(String rawTokenOrNull) {
    if (rawTokenOrNull == null || rawTokenOrNull.isBlank()) {
      return;
    }
    refreshTokens
        .findByHash(TokenHash.ofRaw(rawTokenOrNull))
        .filter(token -> !token.isRevoked())
        .ifPresent(
            token ->
                refreshTokens.revokeIfActive(
                    token.id(), null, clock.instant().truncatedTo(ChronoUnit.MICROS)));
  }
}

package com.nexus.identity.application.auth;

import com.nexus.identity.domain.token.RefreshTokenRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Use case thu hồi cả family trong transaction riêng để không bị rollback theo lỗi của nơi gọi
@Service
public class RevokeRefreshTokenFamilyUseCase {

  private final RefreshTokenRepository refreshTokens;
  private final Clock clock;

  // Nhận kho refresh token và đồng hồ qua constructor
  public RevokeRefreshTokenFamilyUseCase(RefreshTokenRepository refreshTokens, Clock clock) {
    this.refreshTokens = refreshTokens;
    this.clock = clock;
  }

  // Thu hồi mọi token còn hoạt động của family và commit ngay, trả số dòng đã đổi
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int execute(UUID familyId) {
    return refreshTokens.revokeFamily(familyId, clock.instant().truncatedTo(ChronoUnit.MICROS));
  }
}

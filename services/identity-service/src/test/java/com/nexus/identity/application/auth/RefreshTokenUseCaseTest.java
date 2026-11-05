package com.nexus.identity.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.identity.application.port.AccessToken;
import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.application.port.RefreshTokenGenerator;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

// Kiểm tra RefreshTokenUseCase: xoay vòng cùng family, phát hiện dùng lại và thua cuộc đua
class RefreshTokenUseCaseTest {

  private static final Instant NOW =
      Instant.parse("2026-10-24T10:00:00.123456789Z").truncatedTo(ChronoUnit.MICROS);
  private static final Duration TTL = Duration.ofDays(30);
  private static final String OLD_RAW = "old-raw";
  private static final String NEW_RAW = "new-raw";

  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final TokenIssuer tokens = mock(TokenIssuer.class);
  private final RefreshTokenGenerator generator = mock(RefreshTokenGenerator.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final RefreshTokenUseCase useCase =
      new RefreshTokenUseCase(
          refreshTokens,
          users,
          tokens,
          generator,
          new RevokeRefreshTokenFamilyUseCase(refreshTokens, clock),
          clock,
          TTL);
  private final User user = user();
  private final UUID family = UUID.randomUUID();

  // Dựng sẵn generator, user và token phát mới
  RefreshTokenUseCaseTest() {
    when(generator.generate())
        .thenReturn(new GeneratedRefreshToken(NEW_RAW, TokenHash.ofRaw(NEW_RAW)));
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    when(tokens.issue(user)).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));
    when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  // Kịch bản: token còn hiệu lực được xoay vòng sang token mới cùng family
  // Input: token chưa thu hồi chưa hết hạn, revokeIfActive trả true
  // Output: lưu token mới cùng family trước rồi thu hồi token cũ trỏ tới nó, trả raw mới và hạn
  @Test
  void acceptedTokenIsRotatedWithSameFamily() {
    RefreshToken old = stored(NOW.minusSeconds(60), null);
    when(refreshTokens.revokeIfActive(eq(old.id()), any(), eq(NOW))).thenReturn(true);

    RefreshResult result = useCase.execute(OLD_RAW, "ua", "10.0.0.2");

    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    InOrder order = inOrder(refreshTokens);
    order.verify(refreshTokens).save(saved.capture());
    order.verify(refreshTokens).revokeIfActive(old.id(), saved.getValue().id(), NOW);
    RefreshToken fresh = saved.getValue();
    assertThat(fresh.familyId()).isEqualTo(family);
    assertThat(fresh.userId()).isEqualTo(user.id());
    assertThat(fresh.tokenHash()).isEqualTo(TokenHash.ofRaw(NEW_RAW));
    assertThat(fresh.expiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(fresh.userAgent()).isEqualTo("ua");
    assertThat(fresh.ip()).isEqualTo("10.0.0.2");
    assertThat(result.accessToken().value()).isEqualTo("jwt");
    assertThat(result.refreshTokenRaw()).isEqualTo(NEW_RAW);
    assertThat(result.refreshExpiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(result.toString()).doesNotContain(NEW_RAW);
    verify(refreshTokens, never()).revokeFamily(any(), any());
  }

  // Kịch bản: token đã thu hồi bị xuất trình lại thì thu hồi cả family và báo dùng lại
  // Input: token có revokedAt, kể cả khi đồng thời đã hết hạn
  // Output: revokeFamily(family, now) được gọi, ném RefreshTokenReusedException, không phát token
  @Test
  void reuseRevokesFamilyAndThrowsReused() {
    stored(NOW.minusSeconds(60), NOW.minusSeconds(30));

    assertThatThrownBy(() -> useCase.execute(OLD_RAW, "ua", "ip"))
        .isInstanceOf(RefreshTokenReusedException.class);
    verify(refreshTokens).revokeFamily(family, NOW);

    stored(NOW.minus(TTL).minusSeconds(60), NOW.minusSeconds(30));
    assertThatThrownBy(() -> useCase.execute(OLD_RAW, "ua", "ip"))
        .isInstanceOf(RefreshTokenReusedException.class);
    verify(refreshTokens, never()).save(any());
    verify(tokens, never()).issue(any());
  }

  // Kịch bản: hai request cùng token, request này thua ở bước thu hồi có điều kiện
  // Input: token còn hiệu lực nhưng revokeIfActive trả false
  // Output: revokeFamily(family, now) được gọi và ném RefreshTokenReusedException
  @Test
  void lostRaceOnRevokeIfActiveIsTreatedAsReuse() {
    RefreshToken old = stored(NOW.minusSeconds(60), null);
    when(refreshTokens.revokeIfActive(eq(old.id()), any(), eq(NOW))).thenReturn(false);

    assertThatThrownBy(() -> useCase.execute(OLD_RAW, "ua", "ip"))
        .isInstanceOf(RefreshTokenReusedException.class);
    verify(refreshTokens).revokeFamily(family, NOW);
  }

  // Kịch bản: token lạ, hết hạn, rỗng hoặc của user không còn đều là lỗi chung
  // Input: hash không có; token chưa thu hồi đã hết hạn; chuỗi rỗng; user bị xóa
  // Output: cả bốn ném InvalidRefreshTokenException, không thu hồi family nào
  @Test
  void unknownExpiredBlankOrOrphanTokenIsInvalid() {
    when(refreshTokens.findByHash(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> useCase.execute("nope", "ua", "ip"))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThatThrownBy(() -> useCase.execute(" ", "ua", "ip"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    stored(NOW.minus(TTL), null);
    assertThatThrownBy(() -> useCase.execute(OLD_RAW, "ua", "ip"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    stored(NOW.minusSeconds(60), null);
    when(users.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> useCase.execute(OLD_RAW, "ua", "ip"))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(refreshTokens, never()).revokeFamily(any(), any());
    verify(refreshTokens, never()).revokeIfActive(any(), any(), any());
    verify(tokens, never()).issue(any());
  }

  // Đặt token OLD_RAW trong kho với thời điểm phát và thu hồi cho trước, hạn 30 ngày
  private RefreshToken stored(Instant createdAt, Instant revokedAt) {
    RefreshToken token =
        RefreshToken.rehydrate(
            UUID.randomUUID(),
            user.id(),
            family,
            TokenHash.ofRaw(OLD_RAW),
            createdAt.plus(TTL),
            revokedAt,
            null,
            createdAt,
            "ua",
            "ip");
    when(refreshTokens.findByHash(TokenHash.ofRaw(OLD_RAW))).thenReturn(Optional.of(token));
    return token;
  }

  // Dựng user mẫu
  private static User user() {
    return User.register(
        UserId.newId(),
        new Email("a@x.io"),
        new Username("alice"),
        new DisplayName("Alice"),
        new PasswordHash("$2a$12$real"),
        Instant.EPOCH);
  }
}

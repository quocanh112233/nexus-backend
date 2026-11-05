package com.nexus.identity.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.identity.application.port.AccessToken;
import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.application.port.PasswordHasher;
import com.nexus.identity.application.port.RefreshTokenGenerator;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.RawPassword;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra LoginUseCase: phát token khi đúng, lỗi chung và vẫn chạy BCrypt khi sai
class LoginUseCaseTest {

  private static final PasswordHash DUMMY = new PasswordHash("$2a$12$dummy");
  private static final PasswordHash REAL = new PasswordHash("$2a$12$real");

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordHasher hasher = mock(PasswordHasher.class);
  private static final Instant NOW = Instant.parse("2026-10-24T10:00:00.123456789Z");
  private static final Duration TTL = Duration.ofDays(30);

  private final TokenIssuer tokens = mock(TokenIssuer.class);
  private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
  private final RefreshTokenGenerator generator = mock(RefreshTokenGenerator.class);
  private final LoginUseCase useCase;

  // Dựng use case với hash giả cố định do hasher mock trả về lúc khởi tạo và đồng hồ cố định
  LoginUseCaseTest() {
    when(hasher.hash(any())).thenReturn(DUMMY);
    when(generator.generate())
        .thenReturn(new GeneratedRefreshToken("raw-token", TokenHash.ofRaw("raw-token")));
    when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
    useCase =
        new LoginUseCase(
            users, hasher, tokens, refreshTokens, generator, Clock.fixed(NOW, ZoneOffset.UTC), TTL);
  }

  // Kịch bản: email tồn tại và mật khẩu khớp thì phát access token
  // Input: user có hash REAL, hasher.matches trả true
  // Output: LoginResult chứa token do TokenIssuer phát
  @Test
  void validCredentialsIssueToken() {
    User user = user();
    AccessToken token = new AccessToken("jwt", Instant.EPOCH);
    when(users.findByEmail(new Email("a@x.io"))).thenReturn(Optional.of(user));
    when(hasher.matches(any(), eq(REAL))).thenReturn(true);
    when(tokens.issue(user)).thenReturn(token);

    LoginResult result = useCase.execute(command("A@x.io", "secret-pass-1"));

    assertThat(result.accessToken()).isEqualTo(token);
  }

  // Kịch bản: đăng nhập thành công phát refresh token family mới, DB chỉ nhận chuỗi băm
  // Input: thông tin đúng, generator trả raw-token, đồng hồ cố định có nano giây
  // Output: lưu token family mới hash của raw, hạn now+30d cắt micro; result trả raw và hạn đó
  @Test
  void issuesRefreshTokenWithNewFamilyAndStoresOnlyHash() {
    User user = user();
    when(users.findByEmail(any())).thenReturn(Optional.of(user));
    when(hasher.matches(any(), eq(REAL))).thenReturn(true);
    when(tokens.issue(user)).thenReturn(new AccessToken("jwt", Instant.EPOCH));

    LoginResult first = useCase.execute(command("a@x.io", "secret-pass-1"));
    LoginResult second = useCase.execute(command("a@x.io", "secret-pass-1"));

    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, times(2)).save(saved.capture());
    RefreshToken token = saved.getAllValues().get(0);
    Instant expected = NOW.truncatedTo(ChronoUnit.MICROS).plus(TTL);
    assertThat(token.userId()).isEqualTo(user.id());
    assertThat(token.tokenHash()).isEqualTo(TokenHash.ofRaw("raw-token"));
    assertThat(token.tokenHash().value()).doesNotContain("raw-token");
    assertThat(token.expiresAt()).isEqualTo(expected);
    assertThat(token.isRevoked()).isFalse();
    assertThat(token.userAgent()).isEqualTo("junit");
    assertThat(token.ip()).isEqualTo("127.0.0.1");
    assertThat(token.familyId()).isNotEqualTo(saved.getAllValues().get(1).familyId());
    assertThat(first.refreshTokenRaw()).isEqualTo("raw-token");
    assertThat(first.refreshExpiresAt()).isEqualTo(expected);
    assertThat(first.toString()).doesNotContain("raw-token");
    assertThat(second.refreshTokenRaw()).isEqualTo("raw-token");
  }

  // Kịch bản: email không tồn tại vẫn đối chiếu mật khẩu với hash giả để thời gian tương đương
  // Input: findByEmail trả rỗng
  // Output: ném InvalidCredentialsException, matches gọi một lần với DUMMY, không phát token nào
  @Test
  void unknownEmailStillRunsMatchesAgainstDummyHash() {
    when(users.findByEmail(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(command("ghost@x.io", "secret-pass-1")))
        .isInstanceOf(InvalidCredentialsException.class)
        .hasMessage("Invalid credentials");
    verify(hasher, times(1)).matches(new RawPassword("secret-pass-1"), DUMMY);
    verify(tokens, never()).issue(any());
    verify(refreshTokens, never()).save(any());
  }

  // Kịch bản: sai mật khẩu hoặc input sai định dạng đều ném cùng InvalidCredentialsException
  // Input: mật khẩu sai cho user có thật; email sai định dạng; mật khẩu rỗng
  // Output: cả ba ném InvalidCredentialsException, không phát token nào
  @Test
  void wrongPasswordOrMalformedInputThrowInvalidCredentials() {
    when(users.findByEmail(any())).thenReturn(Optional.of(user()));
    when(hasher.matches(any(), any())).thenReturn(false);

    assertThatThrownBy(() -> useCase.execute(command("a@x.io", "wrong-pass-1")))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> useCase.execute(command("not-an-email", "secret-pass-1")))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> useCase.execute(command("a@x.io", "")))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(tokens, never()).issue(any());
    verify(refreshTokens, never()).save(any());
  }

  // Dựng lệnh đăng nhập với user agent và ip cố định
  private static LoginCommand command(String email, String password) {
    return new LoginCommand(email, password, "junit", "127.0.0.1");
  }

  // Dựng user có hash REAL
  private static User user() {
    return User.register(
        UserId.newId(),
        new Email("a@x.io"),
        new Username("alice"),
        new DisplayName("Alice"),
        REAL,
        Instant.EPOCH);
  }
}

package com.nexus.identity.application.auth;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.application.port.PasswordHasher;
import com.nexus.identity.application.port.RefreshTokenGenerator;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.shared.DomainValidationException;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.RawPassword;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đăng nhập: đối chiếu mật khẩu, luôn chạy BCrypt, phát access và refresh token family mới
@Service
public class LoginUseCase {

  private final UserRepository userRepository;
  private final PasswordHasher passwordHasher;
  private final TokenIssuer tokenIssuer;
  private final RefreshTokenRepository refreshTokens;
  private final RefreshTokenGenerator generator;
  private final Clock clock;
  private final Duration refreshTtl;
  private final PasswordHash dummyHash;

  // Nhận các cổng, TTL refresh và băm sẵn một mật khẩu ngẫu nhiên làm hash giả cho email lạ
  public LoginUseCase(
      UserRepository userRepository,
      PasswordHasher passwordHasher,
      TokenIssuer tokenIssuer,
      RefreshTokenRepository refreshTokens,
      RefreshTokenGenerator generator,
      Clock clock,
      @Value("${app.refresh.ttl}") Duration refreshTtl) {
    this.userRepository = userRepository;
    this.passwordHasher = passwordHasher;
    this.tokenIssuer = tokenIssuer;
    this.refreshTokens = refreshTokens;
    this.generator = generator;
    this.clock = clock;
    this.refreshTtl = refreshTtl;
    this.dummyHash = passwordHasher.hash(new RawPassword(UUID.randomUUID().toString()));
  }

  // Xác thực rồi phát access token và lưu hash refresh token mới, sai thì ném lỗi chung
  @Transactional
  public LoginResult execute(LoginCommand command) {
    Email email;
    RawPassword password;
    try {
      email = new Email(command.email());
      password = new RawPassword(command.password());
    } catch (DomainValidationException e) {
      throw new InvalidCredentialsException();
    }
    Optional<User> user = userRepository.findByEmail(email);
    boolean matches =
        passwordHasher.matches(password, user.map(User::passwordHash).orElse(dummyHash));
    if (user.isEmpty() || !matches) {
      throw new InvalidCredentialsException();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    GeneratedRefreshToken generated = generator.generate();
    RefreshToken refreshToken =
        refreshTokens.save(
            RefreshToken.issue(
                UuidCreator.getTimeOrderedEpoch(),
                user.get().id(),
                UUID.randomUUID(),
                generated.hash(),
                now,
                refreshTtl,
                command.userAgent(),
                command.ip()));
    return new LoginResult(
        tokenIssuer.issue(user.get()), generated.raw(), refreshToken.expiresAt());
  }
}

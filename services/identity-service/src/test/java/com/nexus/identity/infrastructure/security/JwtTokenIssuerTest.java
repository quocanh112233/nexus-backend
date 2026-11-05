package com.nexus.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.TestJwtKeys;
import com.nexus.identity.application.port.AccessToken;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.Username;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Kiểm tra JwtTokenIssuer ký RS256 với khóa sinh lúc chạy và đủ claim theo Global Constraints
class JwtTokenIssuerTest {

  private static final KeyPair KEYS = TestJwtKeys.generate();
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  private final JwtTokenIssuer issuer =
      new JwtTokenIssuer(
          new JwtKeyProvider(TestJwtKeys.writePem(KEYS.getPrivate()).toString(), "kid-test"),
          Clock.fixed(NOW, ZoneOffset.UTC),
          "nexus-identity",
          "nexus",
          Duration.ofMinutes(15));

  // Kịch bản: token phát ra verify được bằng public key và mang đủ claim, header có kid
  // Input: user mới, Clock cố định tại NOW, TTL 15 phút
  // Output: sub, username, iss, aud, jti đúng; exp - iat = 900s; kid = kid-test; alg RS256
  @Test
  void tokenVerifiesWithPublicKeyAndHasClaims() {
    User user = user();

    AccessToken token = issuer.issue(user);

    Jwt jwt =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic())
            .build()
            .decode(token.value());
    assertThat(jwt.getSubject()).isEqualTo(user.id().value().toString());
    assertThat(jwt.getClaimAsString("username")).isEqualTo(user.username().value());
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("nexus-identity");
    assertThat(jwt.getAudience()).containsExactly("nexus");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).hasSeconds(900);
    assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
    assertThat(jwt.getHeaders()).containsEntry("kid", "kid-test");
    assertThat(jwt.getHeaders().get("alg")).hasToString("RS256");
  }

  // Kịch bản: token do issuer ký không qua được decoder dùng public key của cặp khóa khác
  // Input: token phát bởi KEYS, decoder dựng từ public key của cặp khóa mới sinh
  // Output: decode ném JwtException
  @Test
  void tokenSignedByOtherKeyIsRejected() {
    String token = issuer.issue(user()).value();
    var otherDecoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) TestJwtKeys.generate().getPublic()).build();

    assertThatThrownBy(() -> otherDecoder.decode(token)).isInstanceOf(JwtException.class);
  }

  // Dựng user mới chưa lưu để phát token
  private static User user() {
    return User.register(
        UserId.newId(),
        new Email("alice@x.io"),
        new Username("alice"),
        new DisplayName("Alice"),
        new PasswordHash("$2a$12$hash"),
        NOW);
  }
}

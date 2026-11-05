package com.nexus.identity.infrastructure.security;

import com.nexus.identity.application.port.AccessToken;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.user.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

// Phát access token JWT RS256 có kid, claim sub, username, iat, exp, iss, aud, jti
@Component
class JwtTokenIssuer implements TokenIssuer {

  private final JwtEncoder encoder;
  private final String keyId;
  private final Clock clock;
  private final String issuer;
  private final String audience;
  private final Duration accessTtl;

  // Dựng encoder Nimbus từ khóa của JwtKeyProvider và đọc cấu hình app.jwt
  JwtTokenIssuer(
      JwtKeyProvider keys,
      Clock clock,
      @Value("${app.jwt.issuer}") String issuer,
      @Value("${app.jwt.audience}") String audience,
      @Value("${app.jwt.access-ttl}") Duration accessTtl) {
    this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.rsaKey())));
    this.keyId = keys.rsaKey().getKeyID();
    this.clock = clock;
    this.issuer = issuer;
    this.audience = audience;
    this.accessTtl = accessTtl;
  }

  // Ký token cho user, iat làm tròn giây để exp - iat đúng bằng TTL
  @Override
  public AccessToken issue(User user) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = now.plus(accessTtl);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(user.id().value().toString())
            .claim("username", user.username().value())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .issuer(issuer)
            .audience(List.of(audience))
            .id(UUID.randomUUID().toString())
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(value, expiresAt);
  }
}

package com.nexus.identity.infrastructure.security;

import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.application.port.RefreshTokenGenerator;
import com.nexus.identity.domain.token.TokenHash;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

// Sinh refresh token 256-bit từ SecureRandom, mã hóa base64url không padding
@Component
class SecureRandomRefreshTokenGenerator implements RefreshTokenGenerator {

  private final SecureRandom random = new SecureRandom();

  // Sinh 32 byte ngẫu nhiên và băm SHA-256
  @Override
  public GeneratedRefreshToken generate() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new GeneratedRefreshToken(raw, TokenHash.ofRaw(raw));
  }
}

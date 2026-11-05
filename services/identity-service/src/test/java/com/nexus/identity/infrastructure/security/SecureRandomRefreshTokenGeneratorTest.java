package com.nexus.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.application.port.GeneratedRefreshToken;
import com.nexus.identity.domain.token.TokenHash;
import org.junit.jupiter.api.Test;

// Kiểm tra SecureRandomRefreshTokenGenerator sinh token ngẫu nhiên đúng định dạng
class SecureRandomRefreshTokenGeneratorTest {

  private final SecureRandomRefreshTokenGenerator generator =
      new SecureRandomRefreshTokenGenerator();

  // Kịch bản: sinh hai token liên tiếp
  // Input: hai lần gọi generate
  // Output: raw khác nhau, dài 43 ký tự base64url, hash bằng TokenHash.ofRaw(raw)
  @Test
  void generatesDistinctUrlSafeTokensWithMatchingHash() {
    GeneratedRefreshToken a = generator.generate();
    GeneratedRefreshToken b = generator.generate();

    assertThat(a.raw()).isNotEqualTo(b.raw());
    assertThat(a.raw()).matches("[A-Za-z0-9_-]{43}");
    assertThat(a.hash()).isEqualTo(TokenHash.ofRaw(a.raw()));
  }

  // Kịch bản: token vừa sinh bị in ra log
  // Input: toString của GeneratedRefreshToken do generate trả về
  // Output: chuỗi không chứa raw và không chứa giá trị hash
  @Test
  void toStringDoesNotLeakRawOrHash() {
    GeneratedRefreshToken token = generator.generate();

    assertThat(token.toString()).doesNotContain(token.raw()).doesNotContain(token.hash().value());
  }
}

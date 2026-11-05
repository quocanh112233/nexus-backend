package com.nexus.identity.domain.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Kiểm tra TokenHash băm SHA-256 dạng hex
class TokenHashTest {

  // Kịch bản: băm chuỗi thô là abc
  // Input: ofRaw("abc")
  // Output: hex SHA-256 chuẩn ba7816bf...15ad, lặp lại cho cùng kết quả
  @Test
  void ofRawIsDeterministicSha256() {
    String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    assertThat(TokenHash.ofRaw("abc").value()).isEqualTo(expected);
    assertThat(TokenHash.ofRaw("abc")).isEqualTo(TokenHash.ofRaw("abc"));
  }
}

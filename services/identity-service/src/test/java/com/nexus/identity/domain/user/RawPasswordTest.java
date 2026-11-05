package com.nexus.identity.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử giới hạn độ dài và che giá trị của RawPassword
class RawPasswordTest {

  // Kịch bản: mật khẩu 7 ký tự bị từ chối
  // Input: "1234567"
  // Output: ném DomainValidationException
  @Test
  void rejects7Chars() {
    assertThatThrownBy(() -> new RawPassword("1234567"))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: mật khẩu quá 72 byte UTF-8 bị từ chối
  // Input: 73 ký tự ASCII và 40 ký tự có dấu (80 byte)
  // Output: cả hai ném DomainValidationException
  @Test
  void rejects73Bytes() {
    assertThatThrownBy(() -> new RawPassword("a".repeat(73)))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new RawPassword("é".repeat(40)))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: toString không làm lộ mật khẩu
  // Input: RawPassword("secret-pass")
  // Output: toString trả "****"
  @Test
  void toStringIsMasked() {
    assertThat(new RawPassword("secret-pass")).hasToString("****");
  }
}

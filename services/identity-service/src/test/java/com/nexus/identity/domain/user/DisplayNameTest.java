package com.nexus.identity.domain.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử kiểm tra hợp lệ của DisplayName
class DisplayNameTest {

  // Kịch bản: tên hiển thị rỗng hoặc toàn khoảng trắng bị từ chối
  // Input: "   "
  // Output: ném DomainValidationException
  @Test
  void rejectsBlank() {
    assertThatThrownBy(() -> new DisplayName("   ")).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: tên hiển thị 33 ký tự bị từ chối
  // Input: "a" lặp 33 lần
  // Output: ném DomainValidationException
  @Test
  void rejects33Chars() {
    assertThatThrownBy(() -> new DisplayName("a".repeat(33)))
        .isInstanceOf(DomainValidationException.class);
  }
}

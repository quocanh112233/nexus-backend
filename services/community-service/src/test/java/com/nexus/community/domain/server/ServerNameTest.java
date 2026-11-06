package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử chuẩn hóa và kiểm tra hợp lệ của ServerName
class ServerNameTest {

  // Kịch bản: khoảng trắng hai đầu bị cắt bỏ
  // Input: " Nexus "
  // Output: giá trị là "Nexus"
  @Test
  void trims() {
    assertThat(new ServerName(" Nexus ").value()).isEqualTo("Nexus");
  }

  // Kịch bản: tên rỗng, toàn khoảng trắng hoặc null bị từ chối
  // Input: "", "   " và null
  // Output: cả ba ném DomainValidationException
  @Test
  void rejectsBlankAndNull() {
    assertThatThrownBy(() -> new ServerName("")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new ServerName("   ")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new ServerName(null)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: biên độ dài 100 và 101
  // Input: 100 ký tự và 101 ký tự
  // Output: 100 hợp lệ, 101 ném DomainValidationException
  @Test
  void lengthBoundary() {
    assertThat(new ServerName("a".repeat(100)).value()).hasSize(100);
    assertThatThrownBy(() -> new ServerName("a".repeat(101)))
        .isInstanceOf(DomainValidationException.class);
  }
}

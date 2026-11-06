package com.nexus.community.domain.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử chuẩn hóa và kiểm tra hợp lệ của CategoryName
class CategoryNameTest {

  // Kịch bản: khoảng trắng hai đầu bị cắt bỏ
  // Input: " Text "
  // Output: giá trị là "Text"
  @Test
  void trims() {
    assertThat(new CategoryName(" Text ").value()).isEqualTo("Text");
  }

  // Kịch bản: tên rỗng, null hoặc dài 101 ký tự bị từ chối
  // Input: "  ", null và 101 ký tự
  // Output: cả ba ném DomainValidationException
  @Test
  void rejectsInvalid() {
    assertThatThrownBy(() -> new CategoryName("  ")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new CategoryName(null)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new CategoryName("a".repeat(101)))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: biên độ dài 100 hợp lệ
  // Input: 100 ký tự
  // Output: tạo được CategoryName
  @Test
  void acceptsMaxLength() {
    assertThat(new CategoryName("a".repeat(100)).value()).hasSize(100);
  }
}

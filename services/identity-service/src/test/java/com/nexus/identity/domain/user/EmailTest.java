package com.nexus.identity.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.util.Locale;
import org.junit.jupiter.api.Test;

// Kiểm thử chuẩn hóa và kiểm tra hợp lệ của Email
class EmailTest {

  // Kịch bản: email có khoảng trắng và chữ hoa được chuẩn hóa
  // Input: " A@X.Com "
  // Output: giá trị là "a@x.com"
  @Test
  void normalizesCase() {
    assertThat(new Email(" A@X.Com ").value()).isEqualTo("a@x.com");
  }

  // Kịch bản: email thiếu dấu @ bị từ chối
  // Input: "abc.com"
  // Output: ném DomainValidationException
  @Test
  void rejectsMissingAt() {
    assertThatThrownBy(() -> new Email("abc.com")).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: email dài hơn 254 ký tự bị từ chối
  // Input: 255 ký tự dạng "aaa...@x.com"
  // Output: ném DomainValidationException
  @Test
  void rejectsOver254Chars() {
    String tooLong = "a".repeat(255 - "@x.com".length()) + "@x.com";
    assertThatThrownBy(() -> new Email(tooLong)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: chuẩn hóa email không phụ thuộc locale mặc định
  // Input: locale mặc định "tr", email "INFO@X.COM"
  // Output: giá trị là "info@x.com"
  @Test
  void normalizesIndependentOfDefaultLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      assertThat(new Email("INFO@X.COM").value()).isEqualTo("info@x.com");
    } finally {
      Locale.setDefault(previous);
    }
  }
}

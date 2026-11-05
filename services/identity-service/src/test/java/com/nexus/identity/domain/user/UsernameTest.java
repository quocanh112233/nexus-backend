package com.nexus.identity.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.domain.shared.DomainValidationException;
import java.util.Locale;
import org.junit.jupiter.api.Test;

// Kiểm thử chuẩn hóa và kiểm tra hợp lệ của Username
class UsernameTest {

  // Kịch bản: username chữ hoa được hạ thành chữ thường
  // Input: "Quoc_Anh.1"
  // Output: giá trị là "quoc_anh.1"
  @Test
  void lowercases() {
    assertThat(new Username("Quoc_Anh.1").value()).isEqualTo("quoc_anh.1");
  }

  // Kịch bản: username chứa khoảng trắng hoặc ký tự lạ bị từ chối
  // Input: "a b" và "a!"
  // Output: cả hai ném DomainValidationException
  @Test
  void rejectsSpaceAndUppercaseInvalidChars() {
    assertThatThrownBy(() -> new Username("a b")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new Username("a!")).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: username quá ngắn hoặc quá dài bị từ chối
  // Input: 1 ký tự và 33 ký tự
  // Output: cả hai ném DomainValidationException
  @Test
  void rejectsLength1And33() {
    assertThatThrownBy(() -> new Username("a")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new Username("a".repeat(33)))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: chuẩn hóa username không phụ thuộc locale mặc định
  // Input: locale mặc định "tr", username "INFO"
  // Output: giá trị là "info"
  @Test
  void lowercasesIndependentOfDefaultLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      assertThat(new Username("INFO").value()).isEqualTo("info");
    } finally {
      Locale.setDefault(previous);
    }
  }
}

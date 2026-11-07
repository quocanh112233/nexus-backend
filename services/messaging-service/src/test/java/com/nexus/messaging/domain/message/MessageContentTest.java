package com.nexus.messaging.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm tra MessageContent đếm độ dài theo code point và không strip
class MessageContentTest {

  // Kịch bản: nội dung đúng 2000 code point nhưng 4000 char UTF-16
  // Input: 2000 emoji 👍
  // Output: hợp lệ, giữ nguyên giá trị
  @Test
  void acceptsExactly2000CodePoints() {
    String text = "👍".repeat(2000);

    assertThat(new MessageContent(text).value()).isEqualTo(text);
  }

  // Kịch bản: vượt giới hạn 2000 code point
  // Input: 2001 emoji 👍
  // Output: DomainValidationException
  @Test
  void rejects2001CodePoints() {
    assertThatThrownBy(() -> new MessageContent("👍".repeat(2001)))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: nội dung null bị từ chối còn rỗng được phép để tin chỉ có tệp đính kèm
  // Input: null và chuỗi rỗng
  // Output: null ném DomainValidationException, chuỗi rỗng giữ nguyên
  @Test
  void rejectsNullButAllowsEmpty() {
    assertThat(new MessageContent("").value()).isEmpty();
    assertThatThrownBy(() -> new MessageContent(null))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: khoảng trắng hai đầu được giữ nguyên
  // Input: "  hi  "
  // Output: value vẫn là "  hi  "
  @Test
  void doesNotStrip() {
    assertThat(new MessageContent("  hi  ").value()).isEqualTo("  hi  ");
  }
}

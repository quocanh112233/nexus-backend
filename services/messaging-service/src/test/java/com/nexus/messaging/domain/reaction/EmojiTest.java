package com.nexus.messaging.domain.reaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra giá trị Emoji chấp nhận emoji thật và loại ký tự thường
class EmojiTest {

  // Kịch bản: emoji đơn, có VS16, ZWJ và keycap
  // Input: "👍", "❤️", "🏳️‍🌈", "1️⃣"
  // Output: tạo được Emoji giữ nguyên giá trị
  @ParameterizedTest
  @ValueSource(strings = {"👍", "❤️", "🏳️‍🌈", "1️⃣"})
  void acceptsRealEmoji(String value) {
    assertThat(new Emoji(value).value()).isEqualTo(value);
  }

  // Kịch bản: chuỗi không phải emoji
  // Input: rỗng, "a", "1", "abc", "#", ZWJ đơn lẻ
  // Output: DomainValidationException
  @ParameterizedTest
  @ValueSource(strings = {"", "a", "1", "abc", "#", "‍", "️"})
  void rejectsNonEmoji(String value) {
    assertThatThrownBy(() -> new Emoji(value)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: chuỗi dài hơn giới hạn cột 32 ký tự
  // Input: 32 rồi 33 lần "👍"
  // Output: 32 hợp lệ, 33 ném DomainValidationException; null cũng bị loại
  @Test
  void rejectsMoreThan32Characters() {
    assertThat(new Emoji("👍".repeat(32)).value()).hasSize(64);
    assertThatThrownBy(() -> new Emoji("👍".repeat(33)))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new Emoji(null)).isInstanceOf(DomainValidationException.class);
  }
}

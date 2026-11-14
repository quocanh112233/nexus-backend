package com.nexus.notification.domain.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// Kiểm tra các value object định danh MessageId và UserId từ chối giá trị không hợp lệ
class MessageIdTest {

  // Kịch bản: MessageId từ chối giá trị rỗng
  // Input: new MessageId(null)
  // Output: ném DomainValidationException
  @Test
  void nullIsRejected() {
    assertThatThrownBy(() -> new MessageId(null)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: UserId.of từ chối chuỗi không phải UUID
  // Input: UserId.of("abc")
  // Output: ném DomainValidationException
  @Test
  void userIdOfRejectsGarbage() {
    assertThatThrownBy(() -> UserId.of("abc")).isInstanceOf(DomainValidationException.class);
  }
}

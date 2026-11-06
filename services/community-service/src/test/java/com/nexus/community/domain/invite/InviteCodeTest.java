package com.nexus.community.domain.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import org.junit.jupiter.api.Test;

// Kiểm thử định dạng của InviteCode
class InviteCodeTest {

  // Kịch bản: mã 8 ký tự chữ số hợp lệ
  // Input: "aB3dE6gH"
  // Output: tạo được InviteCode và isWellFormed trả true
  @Test
  void acceptsValid() {
    assertThat(new InviteCode("aB3dE6gH").value()).isEqualTo("aB3dE6gH");
    assertThat(InviteCode.isWellFormed("aB3dE6gH")).isTrue();
  }

  // Kịch bản: mã sai độ dài, sai ký tự hoặc null bị từ chối
  // Input: 7 ký tự, 9 ký tự, "aB3dE6g!" và null
  // Output: constructor ném DomainValidationException, isWellFormed trả false
  @Test
  void rejectsInvalid() {
    for (String bad : new String[] {"aB3dE6g", "aB3dE6gHi", "aB3dE6g!", null}) {
      assertThatThrownBy(() -> new InviteCode(bad)).isInstanceOf(DomainValidationException.class);
      assertThat(InviteCode.isWellFormed(bad)).isFalse();
    }
  }
}

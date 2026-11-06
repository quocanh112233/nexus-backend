package com.nexus.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Kiểm tra PathMask che mã invite và giữ nguyên path khác
class PathMaskTest {

  // Kịch bản: path accept invite chứa mã thật
  // Input: /api/v1/invites/AbC123xY/accept
  // Output: /api/v1/invites/{code}/accept
  @Test
  void masksCodeBeforeSubPath() {
    assertThat(PathMask.mask("/api/v1/invites/AbC123xY/accept"))
        .isEqualTo("/api/v1/invites/{code}/accept");
  }

  // Kịch bản: path xem invite kết thúc bằng mã
  // Input: /api/v1/invites/AbC123xY
  // Output: /api/v1/invites/{code}
  @Test
  void masksTrailingCode() {
    assertThat(PathMask.mask("/api/v1/invites/AbC123xY")).isEqualTo("/api/v1/invites/{code}");
  }

  // Kịch bản: path không liên quan invite hoặc null
  // Input: /api/v1/servers/123/invites và null
  // Output: giữ nguyên, null vẫn là null
  @Test
  void leavesOtherPathsUntouched() {
    assertThat(PathMask.mask("/api/v1/servers/123/invites"))
        .isEqualTo("/api/v1/servers/123/invites");
    assertThat(PathMask.mask(null)).isNull();
  }
}

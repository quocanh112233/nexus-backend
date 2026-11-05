package com.nexus.identity.domain.token;

import java.util.UUID;

// Kết quả quyết định khi xuất trình refresh token để xoay vòng
public sealed interface RotationDecision {

  // Token hợp lệ, cho phép xoay vòng
  record Accept() implements RotationDecision {}

  // Token đã thu hồi mà bị dùng lại, cần thu hồi cả family
  record Reuse(UUID familyId) implements RotationDecision {}

  // Token chưa thu hồi nhưng đã hết hạn
  record Expired() implements RotationDecision {}
}

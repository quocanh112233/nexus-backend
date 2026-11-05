package com.nexus.identity.interfaces.rest;

import com.nexus.identity.domain.user.User;
import java.time.Instant;
import java.util.UUID;

// Hồ sơ đầy đủ của người dùng trả về cho chính họ
public record MeResponse(
    UUID id,
    String email,
    String username,
    String displayName,
    String aboutMe,
    String avatarUrl,
    Instant createdAt) {

  // Dựng phản hồi từ aggregate User
  static MeResponse from(User user) {
    return new MeResponse(
        user.id().value(),
        user.email().value(),
        user.username().value(),
        user.displayName().value(),
        user.aboutMe().value(),
        user.avatar() == null ? null : user.avatar().url(),
        user.createdAt());
  }
}

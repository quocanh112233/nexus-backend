package com.nexus.identity.application.user;

import com.nexus.identity.domain.user.User;
import java.util.UUID;

// Hồ sơ công khai của người dùng, không có email
public record PublicProfile(
    UUID id, String username, String displayName, String avatarUrl, String aboutMe) {

  // Dựng hồ sơ công khai từ aggregate User
  static PublicProfile from(User user) {
    return new PublicProfile(
        user.id().value(),
        user.username().value(),
        user.displayName().value(),
        user.avatar() == null ? null : user.avatar().url(),
        user.aboutMe().value());
  }
}

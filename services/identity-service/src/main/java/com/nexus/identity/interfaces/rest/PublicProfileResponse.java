package com.nexus.identity.interfaces.rest;

import com.nexus.identity.application.user.PublicProfile;
import java.util.UUID;

// Hồ sơ công khai trả về cho người khác, không có email
public record PublicProfileResponse(
    UUID id, String username, String displayName, String avatarUrl, String aboutMe) {

  // Dựng phản hồi từ PublicProfile
  static PublicProfileResponse from(PublicProfile p) {
    return new PublicProfileResponse(
        p.id(), p.username(), p.displayName(), p.avatarUrl(), p.aboutMe());
  }
}

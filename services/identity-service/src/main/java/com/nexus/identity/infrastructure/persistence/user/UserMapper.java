package com.nexus.identity.infrastructure.persistence.user;

import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.MediaRef;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.Username;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate User và UserJpaEntity
@Component
final class UserMapper {

  // Dựng lại aggregate từ entity qua User.rehydrate
  User toDomain(UserJpaEntity e) {
    MediaRef avatar =
        e.getAvatarPublicId() == null
            ? null
            : new MediaRef(e.getAvatarPublicId(), e.getAvatarUrl());
    return User.rehydrate(
        new UserId(e.getId()),
        new Email(e.getEmail()),
        new Username(e.getUsername()),
        new DisplayName(e.getDisplayName()),
        new AboutMe(e.getAboutMe()),
        new PasswordHash(e.getPasswordHash()),
        avatar,
        e.getCreatedAt(),
        e.getUpdatedAt(),
        e.getVersion());
  }

  // Dựng entity từ aggregate, version null nghĩa là bản ghi mới
  UserJpaEntity toJpa(User u) {
    UserJpaEntity e = new UserJpaEntity();
    e.setId(u.id().value());
    e.setEmail(u.email().value());
    e.setUsername(u.username().value());
    e.setPasswordHash(u.passwordHash().value());
    e.setDisplayName(u.displayName().value());
    e.setAboutMe(u.aboutMe().value());
    e.setAvatarPublicId(u.avatar() == null ? null : u.avatar().publicId());
    e.setAvatarUrl(u.avatar() == null ? null : u.avatar().url());
    e.setCreatedAt(u.createdAt());
    e.setUpdatedAt(u.updatedAt());
    e.setVersion(u.version());
    return e;
  }
}

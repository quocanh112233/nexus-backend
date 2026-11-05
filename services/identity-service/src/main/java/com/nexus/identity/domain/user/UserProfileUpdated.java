package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainEvent;

// Sự kiện hồ sơ người dùng đã thay đổi
public record UserProfileUpdated(
    UserId userId, DisplayName displayName, String avatarUrl, AboutMe aboutMe)
    implements DomainEvent {}

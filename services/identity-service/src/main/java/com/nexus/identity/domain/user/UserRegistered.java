package com.nexus.identity.domain.user;

import com.nexus.identity.domain.shared.DomainEvent;

// Sự kiện người dùng vừa đăng ký
public record UserRegistered(UserId userId, Username username, DisplayName displayName)
    implements DomainEvent {}

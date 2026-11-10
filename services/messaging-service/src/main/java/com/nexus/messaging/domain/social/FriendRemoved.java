package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện userId xóa bạn friendId, do use case phát
public record FriendRemoved(UserId userId, UserId friendId) implements DomainEvent {}

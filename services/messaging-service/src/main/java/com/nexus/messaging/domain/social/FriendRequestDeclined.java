package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện lời mời kết bạn bị từ chối
public record FriendRequestDeclined(FriendRequestId requestId, UserId requester, UserId addressee)
    implements DomainEvent {}

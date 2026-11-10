package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện lời mời kết bạn được chấp nhận
public record FriendRequestAccepted(FriendRequestId requestId, UserId requester, UserId addressee)
    implements DomainEvent {}

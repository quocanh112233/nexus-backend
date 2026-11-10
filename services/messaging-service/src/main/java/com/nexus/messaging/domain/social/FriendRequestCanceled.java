package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện lời mời kết bạn bị người gửi hủy
public record FriendRequestCanceled(FriendRequestId requestId, UserId requester, UserId addressee)
    implements DomainEvent {}

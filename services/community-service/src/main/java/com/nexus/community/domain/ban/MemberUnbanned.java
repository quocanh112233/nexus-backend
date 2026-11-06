package com.nexus.community.domain.ban;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;

// Sự kiện user vừa được gỡ ban khỏi server
public record MemberUnbanned(ServerId serverId, UserId userId, UserId unbannedBy)
    implements DomainEvent {}

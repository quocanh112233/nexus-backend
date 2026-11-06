package com.nexus.community.domain.member;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;

// Sự kiện thành viên vừa bị kick khỏi server
public record MemberKicked(ServerId serverId, UserId userId, UserId kickedBy)
    implements DomainEvent {}

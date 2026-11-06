package com.nexus.community.domain.member;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;

// Sự kiện thành viên vừa vào server
public record MemberJoined(ServerId serverId, UserId userId, Instant joinedAt)
    implements DomainEvent {}

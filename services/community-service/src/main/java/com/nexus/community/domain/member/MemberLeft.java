package com.nexus.community.domain.member;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;

// Sự kiện thành viên vừa rời server
public record MemberLeft(ServerId serverId, UserId userId) implements DomainEvent {}

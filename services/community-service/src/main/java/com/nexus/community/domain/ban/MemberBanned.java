package com.nexus.community.domain.ban;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;

// Sự kiện user vừa bị ban khỏi server, reason có thể null, wasMember cho biết họ đang là thành viên
public record MemberBanned(
    ServerId serverId, UserId userId, UserId bannedBy, String reason, boolean wasMember)
    implements DomainEvent {}

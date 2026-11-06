package com.nexus.community.domain.member;

import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.util.List;

// Sự kiện tập role của thành viên vừa thay đổi
public record MemberRolesChanged(ServerId serverId, UserId userId, List<RoleId> roleIds)
    implements DomainEvent {}

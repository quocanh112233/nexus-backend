package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện role vừa được tạo
public record RoleCreated(
    ServerId serverId, RoleId roleId, String name, PermissionSet permissions, int position)
    implements DomainEvent {}

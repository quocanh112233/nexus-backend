package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện role vừa bị xóa
public record RoleDeleted(ServerId serverId, RoleId roleId) implements DomainEvent {}

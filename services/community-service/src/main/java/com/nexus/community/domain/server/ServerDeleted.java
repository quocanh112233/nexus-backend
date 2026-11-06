package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện server vừa bị xóa
public record ServerDeleted(ServerId serverId) implements DomainEvent {}

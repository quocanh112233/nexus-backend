package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện server vừa đổi tên
public record ServerUpdated(ServerId serverId, ServerName name) implements DomainEvent {}

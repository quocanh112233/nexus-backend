package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;

// Sự kiện server vừa được tạo
public record ServerCreated(ServerId serverId, ServerName name, UserId ownerId)
    implements DomainEvent {}

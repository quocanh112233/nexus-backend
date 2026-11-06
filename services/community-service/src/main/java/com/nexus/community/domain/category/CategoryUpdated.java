package com.nexus.community.domain.category;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;

// Sự kiện category vừa đổi tên hoặc vị trí
public record CategoryUpdated(
    ServerId serverId, CategoryId categoryId, CategoryName name, Position position)
    implements DomainEvent {}

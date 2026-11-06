package com.nexus.community.domain.category;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện category vừa bị xóa
public record CategoryDeleted(ServerId serverId, CategoryId categoryId) implements DomainEvent {}

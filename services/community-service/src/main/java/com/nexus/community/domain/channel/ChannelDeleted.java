package com.nexus.community.domain.channel;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện kênh vừa bị xóa
public record ChannelDeleted(ServerId serverId, ChannelId channelId) implements DomainEvent {}

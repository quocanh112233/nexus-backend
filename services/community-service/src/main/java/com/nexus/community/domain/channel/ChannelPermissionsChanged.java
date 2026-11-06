package com.nexus.community.domain.channel;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;

// Sự kiện overwrite quyền của một kênh vừa đổi
public record ChannelPermissionsChanged(ServerId serverId, ChannelId channelId)
    implements DomainEvent {}

package com.nexus.community.domain.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;

// Sự kiện kênh vừa được tạo, categoryId có thể null
public record ChannelCreated(
    ServerId serverId,
    ChannelId channelId,
    CategoryId categoryId,
    ChannelName name,
    Position position)
    implements DomainEvent {}

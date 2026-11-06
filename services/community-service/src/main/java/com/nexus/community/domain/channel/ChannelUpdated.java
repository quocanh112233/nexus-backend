package com.nexus.community.domain.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;

// Sự kiện kênh vừa đổi, mang trạng thái sau thay đổi
public record ChannelUpdated(
    ServerId serverId,
    ChannelId channelId,
    CategoryId categoryId,
    ChannelName name,
    String topic,
    Position position)
    implements DomainEvent {}

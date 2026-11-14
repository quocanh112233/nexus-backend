package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;

// Kênh còn sống kèm server sở hữu; serverId rỗng với kênh DM
public record ChannelScope(ChannelId id, ServerId serverId) {}

package com.nexus.realtime.application.subscription;

import java.util.UUID;

// Phạm vi của kênh xem được: serverId của kênh server, null nếu là kênh DM
public record ChannelScope(UUID serverId) {}

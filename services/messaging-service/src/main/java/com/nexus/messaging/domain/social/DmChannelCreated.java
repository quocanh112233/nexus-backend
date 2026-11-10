package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện kênh DM giữa hai người vừa được tạo
public record DmChannelCreated(ChannelId channelId, UserId low, UserId high)
    implements DomainEvent {}

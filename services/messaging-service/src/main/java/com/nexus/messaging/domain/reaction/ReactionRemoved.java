package com.nexus.messaging.domain.reaction;

import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện user vừa gỡ emoji khỏi tin nhắn
public record ReactionRemoved(MessageId messageId, ChannelId channelId, UserId userId, Emoji emoji)
    implements DomainEvent {}

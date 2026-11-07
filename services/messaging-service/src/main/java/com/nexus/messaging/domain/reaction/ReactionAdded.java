package com.nexus.messaging.domain.reaction;

import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;

// Sự kiện user vừa thả emoji lên tin nhắn
public record ReactionAdded(MessageId messageId, ChannelId channelId, UserId userId, Emoji emoji)
    implements DomainEvent {}

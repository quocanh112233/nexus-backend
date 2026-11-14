package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.DomainEvent;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.UserId;

// Sự kiện read state của user trong một kênh đã đổi
public record ReadStateUpdated(
    UserId userId, ChannelId channelId, MessageId lastReadMessageId, int mentionCount)
    implements DomainEvent {}

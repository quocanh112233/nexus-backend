package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.DomainEvent;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;

// Sự kiện user được mention hợp lệ lần đầu trong một tin; serverId rỗng với tin DM
public record MentionCreated(
    UserId userId,
    ChannelId channelId,
    ServerId serverId,
    MessageId messageId,
    UserId authorId,
    int mentionCount)
    implements DomainEvent {}

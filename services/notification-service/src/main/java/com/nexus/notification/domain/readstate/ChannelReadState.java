package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;

// Dòng read state của user trong một kênh; unread do SQL tính theo quy tắc D1
public record ChannelReadState(
    ChannelId channelId,
    ServerId serverId,
    MessageId lastMessageId,
    MessageId lastReadMessageId,
    int mentionCount,
    boolean unread) {}

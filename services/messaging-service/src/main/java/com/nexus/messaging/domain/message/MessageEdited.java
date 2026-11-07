package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// Sự kiện tin nhắn vừa được sửa, version là phiên bản sau khi lưu
public record MessageEdited(
    MessageId messageId,
    ChannelId channelId,
    UUID serverId,
    String content,
    Set<UserId> mentions,
    Instant editedAt,
    long version)
    implements DomainEvent {}

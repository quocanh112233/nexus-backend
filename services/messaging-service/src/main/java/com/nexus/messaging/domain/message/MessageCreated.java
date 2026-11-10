package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Sự kiện tin nhắn vừa được tạo; serverId (DM), replyToId và nonce có thể null
public record MessageCreated(
    MessageId messageId,
    ChannelId channelId,
    UUID serverId,
    UserId authorId,
    String content,
    MessageId replyToId,
    List<Attachment> attachments,
    Set<UserId> mentions,
    Instant createdAt,
    Nonce nonce)
    implements DomainEvent {}

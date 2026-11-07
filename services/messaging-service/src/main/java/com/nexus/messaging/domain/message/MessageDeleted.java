package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.util.UUID;

// Sự kiện tin nhắn vừa bị xóa mềm bởi deletedBy
public record MessageDeleted(
    MessageId messageId, ChannelId channelId, UUID serverId, UserId deletedBy)
    implements DomainEvent {}

package com.nexus.messaging.domain.reaction;

import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;

// Một lượt thả emoji của user lên tin nhắn, khóa theo (tin, user, emoji)
public record Reaction(MessageId messageId, UserId userId, Emoji emoji, Instant createdAt) {}

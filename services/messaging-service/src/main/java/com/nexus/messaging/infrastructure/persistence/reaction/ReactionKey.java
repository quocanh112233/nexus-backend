package com.nexus.messaging.infrastructure.persistence.reaction;

import java.io.Serializable;
import java.util.UUID;

// Khóa chính ghép (message_id, user_id, emoji) của bảng reactions
record ReactionKey(UUID messageId, UUID userId, String emoji) implements Serializable {}

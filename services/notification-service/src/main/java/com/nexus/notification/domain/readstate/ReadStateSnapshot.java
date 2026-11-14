package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.MessageId;

// Trạng thái đọc sau khi tiến: tin đã đọc cuối và số mention còn lại
public record ReadStateSnapshot(MessageId lastRead, int mentionCount) {}

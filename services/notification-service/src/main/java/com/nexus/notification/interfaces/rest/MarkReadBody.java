package com.nexus.notification.interfaces.rest;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// Body PUT read-states: tin cuối user đã đọc
record MarkReadBody(@NotNull UUID lastReadMessageId) {}

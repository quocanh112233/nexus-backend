package com.nexus.presence.application.presence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Thay đổi hiện diện cần phát; friendIds luôn rỗng ở P6 (P7 thêm bạn bè)
public record PresenceChange(
    UUID userId, String status, Instant changedAt, List<UUID> serverIds, List<UUID> friendIds) {}

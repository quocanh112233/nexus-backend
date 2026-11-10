package com.nexus.presence.application.presence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Thay đổi hiện diện cần phát kèm server và bạn bè của user để gateway định tuyến
public record PresenceChange(
    UUID userId, String status, Instant changedAt, List<UUID> serverIds, List<UUID> friendIds) {}

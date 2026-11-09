package com.nexus.presence.interfaces.rest;

import com.nexus.presence.domain.presence.PresenceStatus;
import java.util.UUID;

// Một phần tử của phản hồi GET /api/v1/presence
record PresenceResponse(UUID userId, PresenceStatus status) {}

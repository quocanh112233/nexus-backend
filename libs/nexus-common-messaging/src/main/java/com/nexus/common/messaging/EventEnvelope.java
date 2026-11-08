package com.nexus.common.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// Body JSON của message event phát lên RabbitMQ
public record EventEnvelope(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String producer,
    String aggregateType,
    UUID aggregateId,
    Map<String, Object> payload) {}

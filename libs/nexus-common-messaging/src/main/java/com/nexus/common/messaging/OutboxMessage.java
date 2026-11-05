package com.nexus.common.messaging;

import java.util.Map;
import java.util.UUID;

// Nội dung một event cần ghi vào outbox trong transaction của use case
public record OutboxMessage(
    String aggregateType,
    UUID aggregateId,
    String eventType,
    int eventVersion,
    String routingKey,
    Map<String, Object> payload) {}

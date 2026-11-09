package com.nexus.realtime.application.subscription;

import java.util.UUID;

// Một subscription STOMP của user trên một session, serverId null với user queue
public record SessionSubscription(
    String sessionId, UUID userId, String subscriptionId, String destination, UUID serverId) {}

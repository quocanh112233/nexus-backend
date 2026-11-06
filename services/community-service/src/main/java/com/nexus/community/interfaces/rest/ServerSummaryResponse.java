package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.ServerSummary;
import java.time.Instant;
import java.util.UUID;

// Phản hồi tóm tắt server
public record ServerSummaryResponse(UUID id, String name, UUID ownerId, Instant createdAt) {

  // Dựng phản hồi từ ServerSummary
  static ServerSummaryResponse from(ServerSummary s) {
    return new ServerSummaryResponse(s.id(), s.name(), s.ownerId(), s.createdAt());
  }
}

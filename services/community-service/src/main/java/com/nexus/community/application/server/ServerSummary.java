package com.nexus.community.application.server;

import com.nexus.community.domain.server.Server;
import java.time.Instant;
import java.util.UUID;

// Tóm tắt server cho danh sách của tôi
public record ServerSummary(UUID id, String name, UUID ownerId, Instant createdAt) {

  // Dựng tóm tắt từ aggregate server
  public static ServerSummary from(Server server) {
    return new ServerSummary(
        server.id().value(), server.name().value(), server.ownerId().value(), server.createdAt());
  }
}

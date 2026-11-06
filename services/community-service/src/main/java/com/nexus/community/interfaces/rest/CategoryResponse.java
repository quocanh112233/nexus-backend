package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.CategoryView;
import java.util.UUID;

// Phản hồi một category
public record CategoryResponse(UUID id, UUID serverId, String name, int position) {

  // Dựng phản hồi từ góc nhìn category và id server
  static CategoryResponse from(UUID serverId, CategoryView v) {
    return new CategoryResponse(v.id(), serverId, v.name(), v.position());
  }
}

package com.nexus.community.application.server;

import com.nexus.community.domain.server.Role;
import java.util.UUID;

// Góc nhìn role trong chi tiết server
public record RoleView(
    UUID id, String name, int color, long permissions, int position, boolean isDefault) {

  // Dựng góc nhìn từ role của aggregate
  public static RoleView from(Role r) {
    return new RoleView(
        r.id().value(), r.name(), r.color(), r.permissions().bits(), r.position(), r.isDefault());
  }
}

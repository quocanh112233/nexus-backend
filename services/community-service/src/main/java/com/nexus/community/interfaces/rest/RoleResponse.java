package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.RoleView;
import java.util.UUID;

// Phản hồi một role, permissions là bitset dạng số
public record RoleResponse(
    UUID id, String name, int color, long permissions, int position, boolean isDefault) {

  // Dựng phản hồi từ góc nhìn role
  static RoleResponse from(RoleView v) {
    return new RoleResponse(
        v.id(), v.name(), v.color(), v.permissions(), v.position(), v.isDefault());
  }
}

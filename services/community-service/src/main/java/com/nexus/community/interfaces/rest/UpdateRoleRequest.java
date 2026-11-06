package com.nexus.community.interfaces.rest;

import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.RoleChanges;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

// Body sửa role một phần, trường vắng là giữ nguyên
public record UpdateRoleRequest(
    @Size(max = 100) String name,
    @Min(0) @Max(0xFFFFFF) Integer color,
    Long permissions,
    @Min(1) Integer position) {

  // Đổi sang RoleChanges, bit âm hoặc ngoài ALL thì ném lỗi miền
  RoleChanges toChanges() {
    return new RoleChanges(
        name, color, permissions == null ? null : PermissionSet.ofBits(permissions), position);
  }
}

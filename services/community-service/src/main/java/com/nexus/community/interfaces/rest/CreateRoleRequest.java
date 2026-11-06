package com.nexus.community.interfaces.rest;

import com.nexus.community.domain.server.PermissionSet;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Body tạo role, color và permissions vắng là 0, position vắng là 1
public record CreateRoleRequest(
    @NotBlank @Size(max = 100) String name,
    @Min(0) @Max(0xFFFFFF) Integer color,
    Long permissions,
    @Min(1) Integer position) {

  // Tập quyền yêu cầu, bit âm hoặc ngoài ALL thì ném lỗi miền
  PermissionSet permissionSet() {
    return PermissionSet.ofBits(permissions == null ? 0 : permissions);
  }
}

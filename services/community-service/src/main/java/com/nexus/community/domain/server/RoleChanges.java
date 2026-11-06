package com.nexus.community.domain.server;

// Các thay đổi muốn áp lên role, trường null nghĩa là giữ nguyên
public record RoleChanges(
    String name, Integer color, PermissionSet permissions, Integer position) {}

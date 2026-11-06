package com.nexus.community.domain.server;

// Role của server, role mặc định là @everyone
public record Role(
    RoleId id,
    String name,
    int color,
    PermissionSet permissions,
    int position,
    boolean isDefault) {}

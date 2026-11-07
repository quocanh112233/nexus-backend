package com.nexus.community.application.server;

import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.ServerId;

// Kết quả quyền trên kênh: server chứa kênh, tư cách thành viên và quyền hiệu lực
public record ChannelPermissionResult(
    ServerId serverId, boolean member, PermissionSet permissions) {}

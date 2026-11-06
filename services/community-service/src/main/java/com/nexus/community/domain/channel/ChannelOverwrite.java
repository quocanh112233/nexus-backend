package com.nexus.community.domain.channel;

import com.nexus.community.domain.server.PermissionSet;
import java.util.UUID;

// Overwrite quyền của một role hoặc member trên một kênh
public record ChannelOverwrite(
    OverwriteTarget type, UUID targetId, PermissionSet allow, PermissionSet deny) {}

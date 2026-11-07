package com.nexus.messaging.application.port;

import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import java.util.Set;
import java.util.UUID;

// Server chứa kênh và các quyền user có trên kênh đó
public record ChannelAccess(UUID serverId, Set<ChannelPermission> granted) {

  // Sao chép bất biến tập quyền
  public ChannelAccess {
    granted = Set.copyOf(granted);
  }

  // Kiểm tra user có quyền p hay không
  public boolean has(ChannelPermission p) {
    return granted.contains(p);
  }

  // Ném ChannelNotFoundException nếu thiếu VIEW_CHANNEL, PermissionDeniedException nếu thiếu p
  public void require(ChannelPermission p) {
    if (!has(ChannelPermission.VIEW_CHANNEL)) {
      throw new ChannelNotFoundException("channel not found");
    }
    if (!has(p)) {
      throw new PermissionDeniedException("missing permission " + p);
    }
  }
}

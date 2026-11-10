package com.nexus.messaging.domain.shared;

// Quyền cấp kênh của messaging: Community cấp cho kênh server, DmAccessPolicy cấp cho DM
public enum ChannelPermission {
  VIEW_CHANNEL,
  SEND_MESSAGES,
  READ_MESSAGE_HISTORY,
  ATTACH_FILES,
  ADD_REACTIONS,
  MANAGE_MESSAGES
}

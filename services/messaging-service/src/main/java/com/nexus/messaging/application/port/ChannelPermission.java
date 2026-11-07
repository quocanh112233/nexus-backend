package com.nexus.messaging.application.port;

// Các quyền cấp kênh mà Community trả về cho messaging
public enum ChannelPermission {
  VIEW_CHANNEL,
  SEND_MESSAGES,
  READ_MESSAGE_HISTORY,
  ATTACH_FILES,
  ADD_REACTIONS,
  MANAGE_MESSAGES
}

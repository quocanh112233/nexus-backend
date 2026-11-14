package com.nexus.notification.interfaces.rest;

import com.nexus.notification.domain.readstate.ChannelReadState;
import java.util.List;
import java.util.UUID;

// Phản hồi read state của một kênh; serverId null với DM, lastReadMessageId null nếu chưa đọc
record ReadStateResponse(
    UUID channelId,
    UUID serverId,
    UUID lastMessageId,
    UUID lastReadMessageId,
    int mentionCount,
    boolean unread) {

  // Phản hồi danh sách read state
  record Items(List<ReadStateResponse> items) {}

  // Phản hồi sau khi đánh dấu đã đọc
  record Marked(UUID channelId, UUID lastReadMessageId, int mentionCount) {}

  // Dựng từ read state miền
  static ReadStateResponse from(ChannelReadState state) {
    return new ReadStateResponse(
        state.channelId().value(),
        state.serverId() == null ? null : state.serverId().value(),
        state.lastMessageId().value(),
        state.lastReadMessageId() == null ? null : state.lastReadMessageId().value(),
        state.mentionCount(),
        state.unread());
  }
}

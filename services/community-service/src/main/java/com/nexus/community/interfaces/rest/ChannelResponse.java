package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.ChannelView;
import java.util.UUID;

// Phản hồi một kênh, categoryId và topic có thể null
public record ChannelResponse(
    UUID id, UUID serverId, UUID categoryId, String name, String topic, int position) {

  // Dựng phản hồi từ góc nhìn kênh và id server
  static ChannelResponse from(UUID serverId, ChannelView v) {
    return new ChannelResponse(v.id(), serverId, v.categoryId(), v.name(), v.topic(), v.position());
  }
}

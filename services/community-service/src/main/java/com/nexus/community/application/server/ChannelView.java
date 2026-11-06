package com.nexus.community.application.server;

import com.nexus.community.domain.channel.Channel;
import java.util.UUID;

// Góc nhìn kênh trong chi tiết server, categoryId và topic có thể null
public record ChannelView(UUID id, UUID categoryId, String name, String topic, int position) {

  // Dựng góc nhìn từ aggregate kênh
  public static ChannelView from(Channel c) {
    return new ChannelView(
        c.id().value(),
        c.categoryId() == null ? null : c.categoryId().value(),
        c.name().value(),
        c.topic(),
        c.position().value());
  }
}

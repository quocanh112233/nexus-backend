package com.nexus.community.infrastructure.persistence.channel;

import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate Channel và ChannelJpaEntity
@Component
final class ChannelMapper {

  // Dựng lại aggregate từ entity qua Channel.rehydrate
  Channel toDomain(ChannelJpaEntity e) {
    return Channel.rehydrate(
        new ChannelId(e.id),
        new ServerId(e.serverId),
        e.categoryId == null ? null : new CategoryId(e.categoryId),
        new ChannelName(e.name),
        e.topic,
        new Position(e.position),
        e.createdAt,
        e.updatedAt,
        e.version);
  }

  // Dựng entity từ aggregate
  ChannelJpaEntity toJpa(Channel c) {
    ChannelJpaEntity e = new ChannelJpaEntity();
    e.id = c.id().value();
    e.serverId = c.serverId().value();
    e.categoryId = c.categoryId() == null ? null : c.categoryId().value();
    e.name = c.name().value();
    e.topic = c.topic();
    e.type = c.type();
    e.position = c.position().value();
    e.createdAt = c.createdAt();
    e.updatedAt = c.updatedAt();
    e.version = c.version();
    return e;
  }
}

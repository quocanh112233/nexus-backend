package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa kênh, chỉ chủ server được phép
@Service
public class DeleteChannelUseCase {

  private final ServerAccess access;
  private final ChannelRepository channelRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public DeleteChannelUseCase(
      ServerAccess access,
      ChannelRepository channelRepository,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.channelRepository = channelRepository;
    this.eventPublisher = eventPublisher;
  }

  // Phát ChannelDeleted rồi xóa kênh
  @Transactional
  public void execute(ServerId serverId, ChannelId channelId, UserId userId) {
    access.requireOwner(serverId, userId);
    Channel channel =
        channelRepository
            .findById(channelId)
            .filter(c -> c.serverId().equals(serverId))
            .orElseThrow(ChannelNotFoundException::new);
    channel.delete();
    channel.pullEvents().forEach(eventPublisher::publish);
    channelRepository.delete(channelId);
  }
}

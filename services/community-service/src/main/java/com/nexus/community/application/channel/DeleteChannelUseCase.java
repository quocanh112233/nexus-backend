package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa kênh, cần MANAGE_CHANNELS hiệu lực trên chính kênh đó
@Service
public class DeleteChannelUseCase {

  private final ServerAccess access;
  private final ChannelRepository channelRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public DeleteChannelUseCase(
      ServerAccess access,
      ChannelRepository channelRepository,
      ChannelOverwriteRepository overwriteRepository,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.channelRepository = channelRepository;
    this.overwriteRepository = overwriteRepository;
    this.eventPublisher = eventPublisher;
  }

  // Phát ChannelDeleted rồi xóa kênh
  @Transactional
  public void execute(ServerId serverId, ChannelId channelId, UserId userId) {
    Authorized auth = access.requireAuthorized(serverId, userId);
    Channel channel =
        channelRepository
            .findById(channelId)
            .filter(c -> c.serverId().equals(serverId))
            .orElseThrow(ChannelNotFoundException::new);
    OverwriteChecks.requireOnChannel(
        auth, overwriteRepository.findByChannel(channelId), Permission.MANAGE_CHANNELS);
    channel.delete();
    channel.pullEvents().forEach(eventPublisher::publish);
    channelRepository.delete(channelId);
  }
}

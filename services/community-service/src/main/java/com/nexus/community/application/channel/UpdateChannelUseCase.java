package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.ChannelView;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelChanges;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case sửa kênh một phần, cần MANAGE_CHANNELS hiệu lực trên chính kênh đó
@Service
public class UpdateChannelUseCase {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final ChannelRepository channelRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, các cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public UpdateChannelUseCase(
      ServerAccess access,
      CategoryRepository categoryRepository,
      ChannelRepository channelRepository,
      ChannelOverwriteRepository overwriteRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.channelRepository = channelRepository;
    this.overwriteRepository = overwriteRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Áp thay đổi, lưu và phát tối đa một ChannelUpdated
  @Transactional
  public ChannelView execute(
      ServerId serverId, ChannelId channelId, UserId userId, ChannelChanges changes) {
    Authorized auth = access.requireAuthorized(serverId, userId);
    Channel channel =
        channelRepository
            .findById(channelId)
            .filter(c -> c.serverId().equals(serverId))
            .orElseThrow(ChannelNotFoundException::new);
    OverwriteChecks.requireOnChannel(
        auth, overwriteRepository.findByChannel(channelId), Permission.MANAGE_CHANNELS);
    if (changes.changeCategory() && changes.categoryId() != null) {
      CategoryChecks.requireInServer(categoryRepository, serverId, changes.categoryId());
    }
    channel.update(changes, clock.instant().truncatedTo(ChronoUnit.MICROS));
    var events = channel.pullEvents();
    if (!events.isEmpty()) {
      channelRepository.save(channel);
      events.forEach(eventPublisher::publish);
    }
    return ChannelView.from(channel);
  }
}

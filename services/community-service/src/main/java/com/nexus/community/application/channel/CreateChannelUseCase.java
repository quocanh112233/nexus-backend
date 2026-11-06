package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.ChannelView;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryRepository;
import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelChanges;
import com.nexus.community.domain.channel.ChannelCreated;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case tạo kênh trong server, cần quyền MANAGE_CHANNELS
@Service
public class CreateChannelUseCase {

  private final ServerAccess access;
  private final CategoryRepository categoryRepository;
  private final ChannelRepository channelRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, các cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public CreateChannelUseCase(
      ServerAccess access,
      CategoryRepository categoryRepository,
      ChannelRepository channelRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.categoryRepository = categoryRepository;
    this.channelRepository = channelRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Tạo kênh, vị trí mặc định là số kênh cùng nhóm, lưu rồi chỉ phát ChannelCreated
  @Transactional
  public ChannelView execute(
      ServerId serverId,
      UserId userId,
      ChannelName name,
      String topic,
      CategoryId categoryId,
      Integer position) {
    access.requirePermission(serverId, userId, Permission.MANAGE_CHANNELS);
    if (categoryId != null) {
      CategoryChecks.requireInServer(categoryRepository, serverId, categoryId);
    }
    Position at =
        new Position(
            position != null
                ? position
                : (int)
                    channelRepository.findAllByServer(serverId).stream()
                        .filter(c -> Objects.equals(c.categoryId(), categoryId))
                        .count());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Channel channel = Channel.create(serverId, categoryId, name, at, now);
    if (topic != null) {
      channel.update(new ChannelChanges(null, topic, null, false, null), now);
    }
    channelRepository.save(channel);
    channel.pullEvents().stream()
        .filter(ChannelCreated.class::isInstance)
        .forEach(eventPublisher::publish);
    return ChannelView.from(channel);
  }
}

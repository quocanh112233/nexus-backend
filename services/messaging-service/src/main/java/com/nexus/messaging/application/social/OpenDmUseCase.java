package com.nexus.messaging.application.social;

import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.NotFriendsException;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case mở DM 1-1 với bạn bè, mỗi cặp chỉ một kênh dù mở lại hay mở đồng thời
@Service
public class OpenDmUseCase {

  // Kết quả mở DM: góc nhìn kênh và cờ vừa tạo mới
  public record OpenDmResult(DmView dm, boolean created) {}

  private final FriendshipRepository friendships;
  private final DmChannelRepository dmChannels;
  private final ChannelRefRepository channelRefs;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các repository, cổng phát sự kiện và đồng hồ qua constructor
  public OpenDmUseCase(
      FriendshipRepository friendships,
      DmChannelRepository dmChannels,
      ChannelRefRepository channelRefs,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.friendships = friendships;
    this.dmChannels = dmChannels;
    this.channelRefs = channelRefs;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Mở hoặc lấy lại DM; chỉ bạn bè mới mở được, thua đua thì đọc lại kênh của người thắng
  @Transactional
  public OpenDmResult execute(UserId me, UserId friend) {
    if (me.equals(friend)) {
      throw new DomainValidationException("cannot open a DM with yourself");
    }
    UserPair pair = UserPair.of(me, friend);
    if (!friendships.exists(pair)) {
      throw new NotFriendsException("not friends");
    }
    var existing = dmChannels.findByPair(pair);
    if (existing.isPresent()) {
      return new OpenDmResult(DmView.of(existing.get(), me), false);
    }
    DmChannel channel = DmChannel.open(UUID.randomUUID(), pair, clock.instant());
    if (!dmChannels.insertIfAbsent(channel)) {
      return new OpenDmResult(DmView.of(dmChannels.findByPair(pair).orElseThrow(), me), false);
    }
    channelRefs.upsertDmChannel(new ChannelId(channel.id()));
    channel.pullEvents().forEach(eventPublisher::publish);
    return new OpenDmResult(DmView.of(channel, me), true);
  }
}

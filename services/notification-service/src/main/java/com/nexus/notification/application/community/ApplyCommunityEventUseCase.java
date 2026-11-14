package com.nexus.notification.application.community;

import com.nexus.notification.application.port.EventInbox;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case áp event community lên projection kênh, thành viên, read state, idempotent theo eventId
@Service
public class ApplyCommunityEventUseCase {

  private final EventInbox inbox;
  private final ChannelActivityRepository activity;
  private final MembershipRepository members;
  private final ReadStateRepository readStates;
  private final MentionRepository mentions;
  private final Clock clock;

  // Nhận inbox của queue community, bốn repository projection và đồng hồ
  public ApplyCommunityEventUseCase(
      @Qualifier("communityEventInbox") EventInbox inbox,
      ChannelActivityRepository activity,
      MembershipRepository members,
      ReadStateRepository readStates,
      MentionRepository mentions,
      Clock clock) {
    this.inbox = inbox;
    this.activity = activity;
    this.members = members;
    this.readStates = readStates;
    this.mentions = mentions;
    this.clock = clock;
  }

  // Bỏ qua event trùng, áp theo loại; ponytail: Joined/Left đảo thứ tự thì lệch tới khi sửa tay
  @Transactional
  public void execute(CommunityEvent event) {
    if (!inbox.firstDelivery(event.eventId())) {
      return;
    }
    switch (event.eventType()) {
      case "ChannelCreated" ->
          activity.upsertChannel(new ChannelId(event.channelId()), new ServerId(event.serverId()));
      case "ChannelDeleted" ->
          activity.tombstoneChannel(
              new ChannelId(event.channelId()), new ServerId(event.serverId()), clock.instant());
      case "MemberJoined" -> {
        ServerId server = new ServerId(event.serverId());
        UserId user = new UserId(event.userId());
        members.addServerMember(server, user, event.joinedAt());
        readStates.baselineForJoin(server, user);
      }
      case "MemberLeft", "MemberKicked", "MemberBanned" -> {
        ServerId server = new ServerId(event.serverId());
        UserId user = new UserId(event.userId());
        members.removeServerMember(server, user);
        readStates.deleteForUserInServer(user, server);
        mentions.deleteForUserInServer(user, server);
      }
      case "ServerDeleted" -> {
        ServerId server = new ServerId(event.serverId());
        activity.tombstoneServer(server, clock.instant());
        members.removeServer(server);
        readStates.deleteForServer(server);
        mentions.deleteForServer(server);
      }
      default -> {}
    }
  }
}

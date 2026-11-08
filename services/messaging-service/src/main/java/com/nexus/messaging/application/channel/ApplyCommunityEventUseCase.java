package com.nexus.messaging.application.channel;

import com.nexus.messaging.application.port.EventInbox;
import com.nexus.messaging.application.port.PermissionVersion;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case áp event community lên channel_refs và phiên bản quyền, idempotent theo eventId
@Service
public class ApplyCommunityEventUseCase {

  private final EventInbox inbox;
  private final ChannelRefRepository channelRefs;
  private final PermissionVersion permissionVersion;
  private final Clock clock;

  // Nhận inbox, kho tham chiếu kênh, phiên bản quyền và đồng hồ
  public ApplyCommunityEventUseCase(
      EventInbox inbox,
      ChannelRefRepository channelRefs,
      PermissionVersion permissionVersion,
      Clock clock) {
    this.inbox = inbox;
    this.channelRefs = channelRefs;
    this.permissionVersion = permissionVersion;
    this.clock = clock;
  }

  // Bỏ qua event trùng, ngược lại cập nhật kênh theo loại rồi bump phiên bản quyền sau cùng
  @Transactional
  public void execute(CommunityEvent event) {
    if (!inbox.firstDelivery(event.eventId())) {
      return;
    }
    Instant now = clock.instant();
    switch (event.eventType()) {
      case "ChannelCreated" ->
          channelRefs.upsertServerChannel(new ChannelId(event.channelId()), event.serverId());
      case "ChannelDeleted" -> {
        channelRefs.tombstoneChannel(new ChannelId(event.channelId()), event.serverId(), now);
        permissionVersion.bump(event.serverId());
      }
      case "ServerDeleted" -> {
        channelRefs.tombstoneServer(event.serverId(), now);
        permissionVersion.bump(event.serverId());
      }
      case "ChannelPermissionsChanged",
          "MemberLeft",
          "MemberKicked",
          "MemberBanned",
          "MemberRolesChanged",
          "RoleUpdated",
          "RoleDeleted" ->
          permissionVersion.bump(event.serverId());
      default -> {}
    }
  }
}

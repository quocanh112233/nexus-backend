package com.nexus.community.application.channel;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelPermissionsChanged;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case gỡ overwrite quyền của role hoặc member trên một kênh, cần MANAGE_ROLES
@Service
public class RemoveChannelOverwriteUseCase {

  private final ServerAccess access;
  private final ChannelRepository channelRepository;
  private final MemberRepository memberRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, các cổng lưu trữ và cổng phát sự kiện qua constructor
  public RemoveChannelOverwriteUseCase(
      ServerAccess access,
      ChannelRepository channelRepository,
      MemberRepository memberRepository,
      ChannelOverwriteRepository overwriteRepository,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.channelRepository = channelRepository;
    this.memberRepository = memberRepository;
    this.overwriteRepository = overwriteRepository;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm tra quyền, kênh, quyền tại kênh và thứ bậc đích rồi gỡ overwrite, không có thì NotFound
  @Transactional
  public void execute(
      ServerId serverId, ChannelId channelId, OverwriteTarget type, UUID targetId, UserId userId) {
    Authorized auth = access.requirePermission(serverId, userId, Permission.MANAGE_ROLES);
    channelRepository
        .findById(channelId)
        .filter(c -> c.serverId().equals(serverId))
        .orElseThrow(ChannelNotFoundException::new);
    List<ChannelOverwrite> rows = overwriteRepository.findByChannel(channelId);
    PermissionSet here = OverwriteChecks.requireChannelManager(auth, rows);
    if (type == OverwriteTarget.ROLE) {
      auth.server()
          .findRole(new RoleId(targetId))
          .filter(r -> !r.isDefault() && !RoleHierarchyPolicy.canManageRole(auth.actor(), r))
          .ifPresent(
              r -> {
                throw new PermissionDeniedException();
              });
    }
    if (type == OverwriteTarget.MEMBER) {
      memberRepository
          .find(serverId, new UserId(targetId))
          .ifPresent(m -> OverwriteChecks.requireMemberManageable(auth, m));
    }
    ChannelOverwrite existing =
        OverwriteChecks.existing(rows, type, targetId).orElseThrow(OverwriteNotFoundException::new);
    OverwriteChecks.requireWithin(here, existing.deny());
    if (!overwriteRepository.remove(channelId, type, targetId)) {
      throw new OverwriteNotFoundException();
    }
    eventPublisher.publish(new ChannelPermissionsChanged(serverId, channelId));
  }
}

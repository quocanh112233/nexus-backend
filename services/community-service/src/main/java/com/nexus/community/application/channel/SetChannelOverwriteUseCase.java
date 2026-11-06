package com.nexus.community.application.channel;

import com.nexus.community.application.member.MemberNotFoundException;
import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.RoleNotFoundException;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.ChannelPermissionsChanged;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đặt overwrite quyền của role hoặc member trên một kênh, cần MANAGE_ROLES
@Service
public class SetChannelOverwriteUseCase {

  private final ServerAccess access;
  private final ChannelRepository channelRepository;
  private final MemberRepository memberRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, các cổng lưu trữ và cổng phát sự kiện qua constructor
  public SetChannelOverwriteUseCase(
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

  // Kiểm tra quyền, kênh, quyền tại kênh, bit, đích rồi ghi đè overwrite và phát một sự kiện
  @Transactional
  public void execute(
      ServerId serverId,
      ChannelId channelId,
      OverwriteTarget type,
      UUID targetId,
      long allowBits,
      long denyBits,
      UserId userId) {
    Authorized auth = access.requirePermission(serverId, userId, Permission.MANAGE_ROLES);
    channelRepository
        .findById(channelId)
        .filter(c -> c.serverId().equals(serverId))
        .orElseThrow(ChannelNotFoundException::new);
    List<ChannelOverwrite> rows = overwriteRepository.findByChannel(channelId);
    PermissionSet here = OverwriteChecks.requireChannelManager(auth, rows);
    PermissionSet allow = PermissionSet.ofBits(allowBits);
    PermissionSet deny = PermissionSet.ofBits(denyBits);
    if (!PermissionSet.CHANNEL_SCOPE.contains(allow)
        || !PermissionSet.CHANNEL_SCOPE.contains(deny)
        || allow.intersects(deny)) {
      throw new DomainValidationException("allow and deny must be disjoint channel permissions");
    }
    requireTarget(auth, serverId, type, targetId);
    PermissionSet lifted =
        OverwriteChecks.existing(rows, type, targetId)
            .map(o -> o.deny().minus(deny))
            .orElse(new PermissionSet(0));
    OverwriteChecks.requireWithin(here, allow.union(lifted));
    overwriteRepository.upsert(channelId, new ChannelOverwrite(type, targetId, allow, deny));
    eventPublisher.publish(new ChannelPermissionsChanged(serverId, channelId));
  }

  // Đích phải thuộc server và nằm dưới actor về thứ bậc, riêng role @everyone thì luôn được
  private void requireTarget(Authorized auth, ServerId serverId, OverwriteTarget type, UUID id) {
    if (type == OverwriteTarget.MEMBER) {
      Member target =
          memberRepository
              .findForUpdate(serverId, new UserId(id))
              .orElseThrow(MemberNotFoundException::new);
      OverwriteChecks.requireMemberManageable(auth, target);
      return;
    }
    Role role = auth.server().findRole(new RoleId(id)).orElseThrow(RoleNotFoundException::new);
    if (!role.isDefault() && !RoleHierarchyPolicy.canManageRole(auth.actor(), role)) {
      throw new PermissionDeniedException();
    }
  }
}

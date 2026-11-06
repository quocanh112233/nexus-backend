package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case xóa role, cần MANAGE_ROLES và role đích thấp hơn role cao nhất của người gọi
@Service
public class DeleteRoleUseCase {

  private final ServerAccess access;
  private final ServerRepository serverRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, các cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public DeleteRoleUseCase(
      ServerAccess access,
      ServerRepository serverRepository,
      ChannelOverwriteRepository overwriteRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.serverRepository = serverRepository;
    this.overwriteRepository = overwriteRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm tra thứ bậc, gỡ role, lưu, dọn overwrite của role rồi phát RoleDeleted
  @Transactional
  public void execute(ServerId serverId, RoleId roleId, UserId userId) {
    Authorized auth = access.requirePermission(serverId, userId, Permission.MANAGE_ROLES);
    Role role = auth.server().findRole(roleId).orElseThrow(RoleNotFoundException::new);
    if (!RoleHierarchyPolicy.canManageRole(auth.actor(), role)) {
      throw new PermissionDeniedException();
    }
    auth.server().removeRole(roleId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    serverRepository.save(auth.server());
    overwriteRepository.removeByTarget(serverId, roleId.value());
    auth.server().pullEvents().forEach(eventPublisher::publish);
  }
}

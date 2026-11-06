package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleChanges;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case sửa role một phần, cần MANAGE_ROLES và role đích, bit đổi, vị trí đích nằm trong tầm
@Service
public class UpdateRoleUseCase {

  private final ServerAccess access;
  private final ServerRepository serverRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public UpdateRoleUseCase(
      ServerAccess access,
      ServerRepository serverRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.serverRepository = serverRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm tra thứ bậc, áp thay đổi, lưu rồi phát tối đa một RoleUpdated
  @Transactional
  public RoleView execute(ServerId serverId, RoleId roleId, UserId userId, RoleChanges changes) {
    Authorized auth = access.requirePermission(serverId, userId, Permission.MANAGE_ROLES);
    Role current = auth.server().findRole(roleId).orElseThrow(RoleNotFoundException::new);
    boolean allowed = RoleHierarchyPolicy.canManageRole(auth.actor(), current);
    if (changes.permissions() != null) {
      PermissionSet changed =
          new PermissionSet(current.permissions().bits() ^ changes.permissions().bits());
      allowed = allowed && RoleHierarchyPolicy.canGrant(auth.actor(), changed);
    }
    if (changes.position() != null) {
      allowed = allowed && RoleHierarchyPolicy.canPlaceAt(auth.actor(), changes.position(), false);
    }
    if (!allowed) {
      throw new PermissionDeniedException();
    }
    auth.server().updateRole(roleId, changes, clock.instant().truncatedTo(ChronoUnit.MICROS));
    serverRepository.save(auth.server());
    auth.server().pullEvents().forEach(eventPublisher::publish);
    return RoleView.from(auth.server().findRole(roleId).orElseThrow());
  }
}

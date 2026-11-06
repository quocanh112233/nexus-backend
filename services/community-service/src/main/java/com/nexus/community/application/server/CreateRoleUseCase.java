package com.nexus.community.application.server;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case tạo role, cần MANAGE_ROLES và chỉ cấp được quyền, vị trí trong tầm của người gọi
@Service
public class CreateRoleUseCase {

  private final ServerAccess access;
  private final ServerRepository serverRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public CreateRoleUseCase(
      ServerAccess access,
      ServerRepository serverRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.serverRepository = serverRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm tra thứ bậc, tạo role (vị trí vắng là 1), lưu rồi phát RoleCreated
  @Transactional
  public RoleView execute(
      ServerId serverId,
      UserId userId,
      String name,
      int color,
      PermissionSet permissions,
      Integer position) {
    Authorized auth = access.requirePermission(serverId, userId, Permission.MANAGE_ROLES);
    int target = position == null ? 1 : position;
    if (!RoleHierarchyPolicy.canGrant(auth.actor(), permissions)
        || !RoleHierarchyPolicy.canPlaceAt(auth.actor(), target, true)) {
      throw new PermissionDeniedException();
    }
    Role role =
        auth.server()
            .createRole(
                name, color, permissions, position, clock.instant().truncatedTo(ChronoUnit.MICROS));
    serverRepository.save(auth.server());
    auth.server().pullEvents().forEach(eventPublisher::publish);
    return RoleView.from(role);
  }
}

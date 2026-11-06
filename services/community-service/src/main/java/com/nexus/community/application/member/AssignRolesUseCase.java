package com.nexus.community.application.member;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case thay tập role của thành viên, cần MANAGE_ROLES và mọi role thêm hoặc bớt nằm trong tầm
@Service
public class AssignRolesUseCase {

  private final ServerAccess access;
  private final MemberRepository memberRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public AssignRolesUseCase(
      ServerAccess access, MemberRepository memberRepository, DomainEventPublisher eventPublisher) {
    this.access = access;
    this.memberRepository = memberRepository;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm tra quyền, khóa hàng đích, kiểm tra role và thứ bậc, thay tập role, đổi thì lưu và phát
  @Transactional
  public MemberView execute(
      ServerId serverId, UserId actorId, UserId targetId, List<RoleId> roles) {
    Authorized auth = access.requirePermission(serverId, actorId, Permission.MANAGE_ROLES);
    Member target =
        memberRepository
            .findForUpdate(serverId, targetId)
            .orElseThrow(MemberNotFoundException::new);
    Set<RoleId> wanted = new HashSet<>(roles);
    if (wanted.size() != roles.size()) {
      throw new DomainValidationException("roleIds must not contain duplicates");
    }
    wanted.forEach(id -> requireAssignable(auth.server(), id));
    Set<RoleId> current = new HashSet<>(target.roleIds());
    Set<RoleId> changed = new HashSet<>(wanted);
    changed.addAll(current);
    Set<RoleId> kept = new HashSet<>(wanted);
    kept.retainAll(current);
    changed.removeAll(kept);
    for (RoleId id : changed) {
      Role role =
          auth.server()
              .findRole(id)
              .orElseThrow(
                  () -> new OptimisticLockingFailureException("role changed concurrently"));
      if (!RoleHierarchyPolicy.canManageRole(auth.actor(), role)) {
        throw new PermissionDeniedException();
      }
    }
    target.replaceRoles(roles);
    List<DomainEvent> events = target.pullEvents();
    if (!events.isEmpty()) {
      try {
        memberRepository.save(target);
      } catch (DataIntegrityViolationException e) {
        throw new OptimisticLockingFailureException("role or member removed concurrently", e);
      }
      events.forEach(eventPublisher::publish);
    }
    return MemberView.from(target);
  }

  // Role phải thuộc server và không phải @everyone, ngược lại 400
  private static void requireAssignable(Server server, RoleId id) {
    Role role =
        server
            .findRole(id)
            .orElseThrow(() -> new DomainValidationException("role not found: " + id.value()));
    if (role.isDefault()) {
      throw new DomainValidationException("@everyone cannot be assigned");
    }
  }
}

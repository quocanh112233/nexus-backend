package com.nexus.community.application.server;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.server.Actor;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.shared.UserId;
import java.util.Optional;

// Kết quả kiểm tra quyền: server, thành viên gọi và actor mang quyền, vị trí role cao nhất
public record Authorized(Server server, Member member, Actor actor) {

  // Vị trí role cao nhất của thành viên đích tính cả @everyone nên tối thiểu 0
  public int highestPositionOf(Member target) {
    return target.roleIds().stream()
        .map(server::findRole)
        .flatMap(Optional::stream)
        .mapToInt(Role::position)
        .max()
        .orElse(0);
  }

  // Ném PermissionDenied nếu actor không kick hoặc ban được đích, đích null là người ngoài
  public void requireCanModerate(UserId target, Member targetMemberOrNull) {
    Integer highest = targetMemberOrNull == null ? null : highestPositionOf(targetMemberOrNull);
    if (!RoleHierarchyPolicy.canModerate(actor, target, server.isOwner(target), highest)) {
      throw new PermissionDeniedException();
    }
  }
}

package com.nexus.community.domain.server;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.shared.UserId;

// Người thực hiện hành động kèm quyền server và vị trí role cao nhất
public record Actor(
    UserId userId, boolean owner, PermissionSet serverPermissions, int highestPosition) {

  // Dựng actor từ server và member, vị trí cao nhất tính cả @everyone
  public static Actor of(Server server, Member member) {
    int highest =
        server.roles().stream()
            .filter(r -> r.isDefault() || member.roleIds().contains(r.id()))
            .mapToInt(Role::position)
            .max()
            .orElse(0);
    return new Actor(
        member.userId(),
        server.isOwner(member.userId()),
        PermissionCalculator.server(server, member),
        highest);
  }
}

package com.nexus.community.domain.server;

import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.Member;
import java.util.List;

// Tính quyền hiệu lực của member theo permission-model.md
public final class PermissionCalculator {

  private static final PermissionSet NONE = new PermissionSet(0);

  // Lớp tiện ích tĩnh, không tạo thể hiện
  private PermissionCalculator() {}

  // Quyền phạm vi server: chủ hoặc ADMINISTRATOR có ALL, còn lại hợp @everyone và role của member
  public static PermissionSet server(Server server, Member member) {
    if (server.isOwner(member.userId())) {
      return PermissionSet.ALL;
    }
    PermissionSet base =
        server.roles().stream()
            .filter(r -> r.isDefault() || member.roleIds().contains(r.id()))
            .map(Role::permissions)
            .reduce(NONE, PermissionSet::union);
    return base.has(Permission.ADMINISTRATOR) ? PermissionSet.ALL : base;
  }

  // Quyền hiệu lực trên kênh theo 7 bước, overwrite chỉ tác động bit phạm vi kênh
  public static PermissionSet channel(
      Server server, Member member, List<ChannelOverwrite> overwrites) {
    PermissionSet base = server(server, member);
    if (base.equals(PermissionSet.ALL)) {
      return base;
    }
    Role everyone = server.roles().stream().filter(Role::isDefault).findFirst().orElseThrow();
    PermissionSet p = base;
    for (ChannelOverwrite ow : overwrites) {
      if (ow.type() == OverwriteTarget.ROLE && ow.targetId().equals(everyone.id().value())) {
        p = apply(p, ow.allow(), ow.deny());
      }
    }
    PermissionSet deny = NONE;
    PermissionSet allow = NONE;
    for (ChannelOverwrite ow : overwrites) {
      boolean memberRole =
          member.roleIds().stream().anyMatch(id -> id.value().equals(ow.targetId()));
      if (ow.type() == OverwriteTarget.ROLE
          && memberRole
          && !ow.targetId().equals(everyone.id().value())) {
        deny = deny.union(ow.deny());
        allow = allow.union(ow.allow());
      }
    }
    p = apply(p, allow, deny);
    for (ChannelOverwrite ow : overwrites) {
      if (ow.type() == OverwriteTarget.MEMBER && ow.targetId().equals(member.userId().value())) {
        p = apply(p, ow.allow(), ow.deny());
      }
    }
    if (!p.has(Permission.VIEW_CHANNEL)) {
      return NONE;
    }
    return p.has(Permission.SEND_MESSAGES) ? p : p.without(Permission.ATTACH_FILES);
  }

  // p = (p trừ deny) hợp allow, chỉ xét bit phạm vi kênh
  private static PermissionSet apply(PermissionSet p, PermissionSet allow, PermissionSet deny) {
    PermissionSet scopedDeny = new PermissionSet(deny.bits() & PermissionSet.CHANNEL_SCOPE.bits());
    PermissionSet scopedAllow =
        new PermissionSet(allow.bits() & PermissionSet.CHANNEL_SCOPE.bits());
    return p.minus(scopedDeny).union(scopedAllow);
  }
}

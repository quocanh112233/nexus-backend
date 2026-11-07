package com.nexus.community.application.channel;

import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionCalculator;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.RoleHierarchyPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Kiểm tra thứ bậc cho overwrite đích member, dùng chung cho đặt và gỡ overwrite
final class OverwriteChecks {

  // Lớp tiện ích không tạo thể hiện
  private OverwriteChecks() {}

  // Chủ server luôn được, còn lại đích không được là chủ hay chính mình và phải thấp hơn actor
  static void requireMemberManageable(Authorized auth, Member target) {
    if (auth.actor().owner()) {
      return;
    }
    Integer highest = auth.highestPositionOf(target);
    if (!RoleHierarchyPolicy.canModerate(
        auth.actor(), target.userId(), auth.server().isOwner(target.userId()), highest)) {
      throw new PermissionDeniedException();
    }
  }

  // Quyền hiệu lực của actor trên kênh, không thấy kênh thì NotFound, thiếu MANAGE_ROLES thì Denied
  static PermissionSet requireChannelManager(Authorized auth, List<ChannelOverwrite> overwrites) {
    return requireOnChannel(auth, overwrites, Permission.MANAGE_ROLES);
  }

  // Quyền hiệu lực của actor trên kênh, không thấy kênh thì NotFound, thiếu quyền đó thì Denied
  static PermissionSet requireOnChannel(
      Authorized auth, List<ChannelOverwrite> overwrites, Permission permission) {
    PermissionSet here = PermissionCalculator.channel(auth.server(), auth.member(), overwrites);
    if (!here.has(Permission.VIEW_CHANNEL)) {
      throw new ChannelNotFoundException();
    }
    if (!here.has(permission)) {
      throw new PermissionDeniedException();
    }
    return here;
  }

  // Overwrite hiện có của đích trong kênh
  static Optional<ChannelOverwrite> existing(
      List<ChannelOverwrite> overwrites, OverwriteTarget type, UUID targetId) {
    return overwrites.stream()
        .filter(o -> o.type() == type && o.targetId().equals(targetId))
        .findFirst();
  }

  // Actor chỉ cấp hoặc gỡ deny của các bit mà chính họ đang có trên kênh này
  static void requireWithin(PermissionSet here, PermissionSet bits) {
    if (!here.contains(bits)) {
      throw new PermissionDeniedException();
    }
  }
}

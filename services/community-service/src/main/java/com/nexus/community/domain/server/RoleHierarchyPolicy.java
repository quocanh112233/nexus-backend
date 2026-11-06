package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.UserId;

// Luật thứ bậc role cho quản lý role, cấp quyền, kick và ban
public final class RoleHierarchyPolicy {

  // Lớp tiện ích tĩnh, không tạo thể hiện
  private RoleHierarchyPolicy() {}

  // Actor quản lý được role khi là chủ hoặc role thấp hơn role cao nhất của actor
  public static boolean canManageRole(Actor actor, Role role) {
    return actor.owner() || role.position() < actor.highestPosition();
  }

  // Actor đặt được role ở vị trí đó, role mới được ngang cấp còn role cũ phải thấp hơn
  public static boolean canPlaceAt(Actor actor, int position, boolean insertingNew) {
    return actor.owner()
        || (insertingNew
            ? position <= actor.highestPosition()
            : position < actor.highestPosition());
  }

  // Actor chỉ cấp được các bit nằm trong quyền server của mình
  public static boolean canGrant(Actor actor, PermissionSet changedBits) {
    return actor.owner() || actor.serverPermissions().contains(changedBits);
  }

  // Actor kick hoặc ban được target, không được nhắm chủ hay chính mình
  public static boolean canModerate(
      Actor actor, UserId target, boolean targetIsOwner, Integer targetHighestOrNull) {
    if (targetIsOwner || actor.userId().equals(target)) {
      return false;
    }
    return actor.owner()
        || targetHighestOrNull == null
        || targetHighestOrNull < actor.highestPosition();
  }
}

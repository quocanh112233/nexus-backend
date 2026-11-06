package com.nexus.community.domain.server;

import java.util.Arrays;

// Tập quyền bất biến lưu dạng bitset
public record PermissionSet(long bits) {

  public static final PermissionSet EVERYONE_DEFAULT =
      new PermissionSet(0)
          .with(
              Permission.CREATE_INVITE,
              Permission.VIEW_CHANNEL,
              Permission.SEND_MESSAGES,
              Permission.READ_MESSAGE_HISTORY,
              Permission.ATTACH_FILES,
              Permission.ADD_REACTIONS);

  public static final PermissionSet ALL = new PermissionSet(0).with(Permission.values());

  // Kiểm tra tập có chứa quyền không
  public boolean has(Permission permission) {
    return (bits & permission.mask()) != 0;
  }

  // Trả tập mới có thêm các quyền
  public PermissionSet with(Permission... permissions) {
    return new PermissionSet(bits | maskOf(permissions));
  }

  // Trả tập mới đã bỏ các quyền
  public PermissionSet without(Permission... permissions) {
    return new PermissionSet(bits & ~maskOf(permissions));
  }

  // Gộp các quyền thành một mặt nạ bit
  private static long maskOf(Permission... permissions) {
    return Arrays.stream(permissions).mapToLong(Permission::mask).reduce(0, (a, b) -> a | b);
  }
}

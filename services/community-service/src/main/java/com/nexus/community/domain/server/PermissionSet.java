package com.nexus.community.domain.server;

import com.nexus.community.domain.shared.DomainValidationException;
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

  // Các quyền áp dụng được ở phạm vi kênh, bit 2 đến 10
  public static final PermissionSet CHANNEL_SCOPE =
      new PermissionSet(0)
          .with(
              Permission.MANAGE_ROLES,
              Permission.MANAGE_CHANNELS,
              Permission.CREATE_INVITE,
              Permission.VIEW_CHANNEL,
              Permission.SEND_MESSAGES,
              Permission.READ_MESSAGE_HISTORY,
              Permission.ATTACH_FILES,
              Permission.ADD_REACTIONS,
              Permission.MANAGE_MESSAGES);

  // Dựng tập từ bit thô, từ chối bit âm hoặc ngoài ALL
  public static PermissionSet ofBits(long bits) {
    if (bits < 0 || (bits & ~ALL.bits) != 0) {
      throw new DomainValidationException("permission bits invalid");
    }
    return new PermissionSet(bits);
  }

  // Cho biết quyền có thuộc phạm vi kênh không
  public static boolean isChannelScoped(Permission permission) {
    return CHANNEL_SCOPE.has(permission);
  }

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

  // Kiểm tra other là tập con của tập này
  public boolean contains(PermissionSet other) {
    return (bits & other.bits) == other.bits;
  }

  // Hợp hai tập quyền
  public PermissionSet union(PermissionSet other) {
    return new PermissionSet(bits | other.bits);
  }

  // Tập này bỏ đi các quyền của other
  public PermissionSet minus(PermissionSet other) {
    return new PermissionSet(bits & ~other.bits);
  }

  // Kiểm tra hai tập có quyền chung
  public boolean intersects(PermissionSet other) {
    return (bits & other.bits) != 0;
  }
}

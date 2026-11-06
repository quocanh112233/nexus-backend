package com.nexus.community.domain.server;

// 13 quyền của server theo permission-model.md, mỗi quyền một bit
public enum Permission {
  ADMINISTRATOR(0),
  MANAGE_SERVER(1),
  MANAGE_ROLES(2),
  MANAGE_CHANNELS(3),
  CREATE_INVITE(4),
  VIEW_CHANNEL(5),
  SEND_MESSAGES(6),
  READ_MESSAGE_HISTORY(7),
  ATTACH_FILES(8),
  ADD_REACTIONS(9),
  MANAGE_MESSAGES(10),
  KICK_MEMBERS(11),
  BAN_MEMBERS(12);

  private final long mask;

  // Gán mặt nạ từ số thứ tự bit
  Permission(int bit) {
    this.mask = 1L << bit;
  }

  // Mặt nạ bit của quyền
  public long mask() {
    return mask;
  }
}

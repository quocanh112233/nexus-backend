package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.ChannelPermission;
import java.util.Set;

// Bảng quyền kênh DM: bạn bè đủ quyền nhắn, hết bạn chỉ đọc, người ngoài không có gì
public final class DmAccessPolicy {

  private static final Set<ChannelPermission> FRIENDS =
      Set.of(
          ChannelPermission.VIEW_CHANNEL,
          ChannelPermission.SEND_MESSAGES,
          ChannelPermission.READ_MESSAGE_HISTORY,
          ChannelPermission.ATTACH_FILES,
          ChannelPermission.ADD_REACTIONS);
  private static final Set<ChannelPermission> READ_ONLY =
      Set.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY);

  // Lớp tiện ích, không khởi tạo
  private DmAccessPolicy() {}

  // Quyền DM theo người tham gia và còn là bạn; không bao giờ có MANAGE_MESSAGES
  public static Set<ChannelPermission> permissions(boolean participant, boolean friends) {
    if (!participant) {
      return Set.of();
    }
    return friends ? FRIENDS : READ_ONLY;
  }
}

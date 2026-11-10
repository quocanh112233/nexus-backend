package com.nexus.messaging.domain.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.domain.shared.ChannelPermission;
import org.junit.jupiter.api.Test;

// Kiểm tra bảng quyền DM theo người tham gia và quan hệ bạn bè
class DmAccessPolicyTest {

  // Kịch bản: hai người tham gia đang là bạn
  // Input: permissions(participant=true, friends=true)
  // Output: đúng 5 quyền xem, gửi, đọc lịch sử, đính kèm, reaction; không có MANAGE_MESSAGES
  @Test
  void friendsGetFullDmPermissions() {
    assertThat(DmAccessPolicy.permissions(true, true))
        .containsExactlyInAnyOrder(
            ChannelPermission.VIEW_CHANNEL,
            ChannelPermission.SEND_MESSAGES,
            ChannelPermission.READ_MESSAGE_HISTORY,
            ChannelPermission.ATTACH_FILES,
            ChannelPermission.ADD_REACTIONS);
  }

  // Kịch bản: người tham gia nhưng đã hủy kết bạn
  // Input: permissions(participant=true, friends=false)
  // Output: chỉ VIEW_CHANNEL và READ_MESSAGE_HISTORY
  @Test
  void exFriendsCanOnlyRead() {
    assertThat(DmAccessPolicy.permissions(true, false))
        .containsExactlyInAnyOrder(
            ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY);
  }

  // Kịch bản: người ngoài kênh DM, kể cả khi là bạn của một bên
  // Input: permissions(false, true) và permissions(false, false)
  // Output: tập rỗng
  @Test
  void nonParticipantGetsNothing() {
    assertThat(DmAccessPolicy.permissions(false, true)).isEmpty();
    assertThat(DmAccessPolicy.permissions(false, false)).isEmpty();
  }
}

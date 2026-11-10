package com.nexus.messaging.infrastructure.persistence.channel;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmAccessPolicy;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;

// Tự quyết quyền DM theo bạn bè, không cache, không gọi Community; kênh khác hỏi delegate
public class DmAwareChannelPermissionChecker implements ChannelPermissionChecker {

  private final ChannelRefRepository channelRefs;
  private final DmChannelRepository dms;
  private final FriendshipRepository friendships;
  private final ChannelPermissionChecker delegate;

  // Nhận kho tham chiếu kênh, kho DM, kho bạn bè và delegate cho kênh server
  public DmAwareChannelPermissionChecker(
      ChannelRefRepository channelRefs,
      DmChannelRepository dms,
      FriendshipRepository friendships,
      ChannelPermissionChecker delegate) {
    this.channelRefs = channelRefs;
    this.dms = dms;
    this.friendships = friendships;
    this.delegate = delegate;
  }

  // DM thì tính quyền từ cặp người và bạn bè, người ngoài hoặc đã xóa là not found
  @Override
  public ChannelAccess check(UserId userId, ChannelId channelId) {
    // ponytail: +1 PK read channel_refs mỗi check; cache kind DM trong bộ nhớ nếu đo thấy chậm
    if (!channelRefs.isDm(channelId)) {
      return delegate.check(userId, channelId);
    }
    if (channelRefs.isDeleted(channelId)) {
      throw new ChannelNotFoundException("channel not found");
    }
    DmChannel dm =
        dms.findById(channelId)
            .filter(c -> c.pair().contains(userId))
            .orElseThrow(() -> new ChannelNotFoundException("channel not found"));
    return new ChannelAccess(null, DmAccessPolicy.permissions(true, friendships.exists(dm.pair())));
  }
}

package com.nexus.messaging.infrastructure.persistence.channel;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;

// Chặn kênh đã bị tombstone bằng ChannelNotFoundException trước khi hỏi delegate
public class TombstoneChannelPermissionChecker implements ChannelPermissionChecker {

  private final ChannelRefRepository channelRefs;
  private final ChannelPermissionChecker delegate;

  // Nhận kho tham chiếu kênh và delegate
  public TombstoneChannelPermissionChecker(
      ChannelRefRepository channelRefs, ChannelPermissionChecker delegate) {
    this.channelRefs = channelRefs;
    this.delegate = delegate;
  }

  // Kênh đã xóa thì ném not found, ngược lại hỏi delegate
  @Override
  public ChannelAccess check(UserId userId, ChannelId channelId) {
    if (channelRefs.isDeleted(channelId)) {
      throw new ChannelNotFoundException("channel not found");
    }
    return delegate.check(userId, channelId);
  }
}

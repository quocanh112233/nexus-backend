package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;

// Kho mention theo (tin, user), khóa chính chặn đếm trùng
public interface MentionRepository {

  // Chèn mention, trả true nếu là dòng mới
  boolean insertIfAbsent(
      MessageId messageId,
      UserId userId,
      ChannelId channelId,
      ServerId serverId,
      UserId authorId,
      Instant createdAt);

  // Xóa mention của user trong mọi kênh của server
  void deleteForUserInServer(UserId userId, ServerId serverId);

  // Xóa mention của mọi user trong server
  void deleteForServer(ServerId serverId);
}

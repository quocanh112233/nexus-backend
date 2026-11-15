package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;

// Kho projection thành viên server và participant DM để kiểm quyền truy cập kênh
public interface MembershipRepository {

  // Thêm thành viên server, bỏ qua nếu đã có
  void addServerMember(ServerId serverId, UserId userId, Instant joinedAt);

  // Xóa một thành viên khỏi server
  void removeServerMember(ServerId serverId, UserId userId);

  // Xóa mọi thành viên của server
  void removeServer(ServerId serverId);

  // Ghi hai participant của kênh DM
  void addDmParticipants(ChannelId channelId, UserId first, UserId second);

  // True nếu user là thành viên server của kênh hoặc participant DM
  boolean canAccess(UserId userId, ChannelScope scope);

  // True nếu user đã là thành viên server (joined_at <= at) hoặc participant DM của kênh
  boolean wasMemberAt(UserId userId, ChannelScope scope, Instant at);
}

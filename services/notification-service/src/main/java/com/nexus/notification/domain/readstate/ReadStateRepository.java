package com.nexus.notification.domain.readstate;

import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.util.List;

// Kho read state theo (user, kênh): mốc đã đọc và số mention chưa đọc
public interface ReadStateRepository {

  // Đặt mốc đã đọc bằng tin mới nhất cho mọi kênh có tin của server khi user vào server
  void baselineForJoin(ServerId serverId, UserId userId);

  // Đẩy mốc đã đọc lên GREATEST(cũ, mới), tính lại mention_count và trả trạng thái sau cùng
  ReadStateSnapshot advance(UserId userId, ChannelId channelId, MessageId messageId);

  // Cộng một mention chưa đọc nếu tin sau mốc đã đọc, trả mention_count mới
  int addMentionCount(UserId userId, ChannelId channelId, MessageId messageId);

  // Xóa read state của user trong mọi kênh của server
  void deleteForUserInServer(UserId userId, ServerId serverId);

  // Xóa read state của mọi user trong server
  void deleteForServer(ServerId serverId);

  // Liệt kê read state của user cho các kênh đã có tin và chưa bị xóa
  List<ChannelReadState> listFor(UserId userId);
}

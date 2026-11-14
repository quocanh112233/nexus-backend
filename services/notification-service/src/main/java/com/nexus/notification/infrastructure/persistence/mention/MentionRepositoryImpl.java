package com.nexus.notification.infrastructure.persistence.mention;

import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;
import org.springframework.stereotype.Repository;

// Cài đặt MentionRepository bằng INSERT ... ON CONFLICT DO NOTHING và DELETE native
@Repository
class MentionRepositoryImpl implements MentionRepository {

  private final MentionDao dao;

  // Nhận DAO qua constructor
  MentionRepositoryImpl(MentionDao dao) {
    this.dao = dao;
  }

  // Chèn mention, true khi chèn thật
  @Override
  public boolean insertIfAbsent(
      MessageId messageId,
      UserId userId,
      ChannelId channelId,
      ServerId serverId,
      UserId authorId,
      Instant createdAt) {
    return dao.insertIfAbsent(
            messageId.value(),
            userId.value(),
            channelId.value(),
            serverId == null ? null : serverId.value(),
            authorId.value(),
            createdAt)
        > 0;
  }

  // Xóa mention của user trong server
  @Override
  public void deleteForUserInServer(UserId userId, ServerId serverId) {
    dao.deleteForUserInServer(userId.value(), serverId.value());
  }

  // Xóa mention của mọi user trong server
  @Override
  public void deleteForServer(ServerId serverId) {
    dao.deleteForServer(serverId.value());
  }
}

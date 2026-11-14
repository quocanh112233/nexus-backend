package com.nexus.notification.infrastructure.persistence.readstate;

import com.nexus.notification.domain.readstate.ChannelReadState;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.readstate.ReadStateSnapshot;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt ReadStateRepository bằng upsert native nguyên tử, mọi so sánh uuid nằm ở SQL
@Repository
class ReadStateRepositoryImpl implements ReadStateRepository {

  private final ReadStateDao dao;

  // Nhận DAO qua constructor
  ReadStateRepositoryImpl(ReadStateDao dao) {
    this.dao = dao;
  }

  // Đặt mốc đọc cho các kênh có tin của server
  @Override
  public void baselineForJoin(ServerId serverId, UserId userId) {
    dao.baselineForJoin(serverId.value(), userId.value());
  }

  // Khóa dòng rồi mới đếm ở câu thứ hai để READ COMMITTED thấy mention vừa commit khi chờ khóa
  @Override
  public ReadStateSnapshot advance(UserId userId, ChannelId channelId, MessageId messageId) {
    dao.advanceLastRead(userId.value(), channelId.value(), messageId.value());
    Object[] row = dao.recountMentions(userId.value(), channelId.value()).get(0);
    return new ReadStateSnapshot(new MessageId((UUID) row[0]), ((Number) row[1]).intValue());
  }

  // Cộng mention nếu tin sau mốc đọc
  @Override
  public int addMentionCount(UserId userId, ChannelId channelId, MessageId messageId) {
    return dao.addMentionCount(userId.value(), channelId.value(), messageId.value());
  }

  // Xóa read state của user trong server
  @Override
  public void deleteForUserInServer(UserId userId, ServerId serverId) {
    dao.deleteForUserInServer(userId.value(), serverId.value());
  }

  // Xóa read state của mọi user trong server
  @Override
  public void deleteForServer(ServerId serverId) {
    dao.deleteForServer(serverId.value());
  }

  // Liệt kê read state của user, unread lấy nguyên từ SQL
  @Override
  public List<ChannelReadState> listFor(UserId userId) {
    return dao.listFor(userId.value()).stream()
        .map(
            r ->
                new ChannelReadState(
                    new ChannelId((UUID) r[0]),
                    r[1] == null ? null : new ServerId((UUID) r[1]),
                    new MessageId((UUID) r[2]),
                    r[3] == null ? null : new MessageId((UUID) r[3]),
                    ((Number) r[4]).intValue(),
                    (Boolean) r[5]))
        .toList();
  }
}

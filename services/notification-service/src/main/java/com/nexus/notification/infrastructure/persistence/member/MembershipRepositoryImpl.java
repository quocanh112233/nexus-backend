package com.nexus.notification.infrastructure.persistence.member;

import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Instant;
import org.springframework.stereotype.Repository;

// Cài đặt MembershipRepository bằng INSERT ... ON CONFLICT DO NOTHING, DELETE và EXISTS native
@Repository
class MembershipRepositoryImpl implements MembershipRepository {

  private final MembershipDao dao;

  // Nhận DAO qua constructor
  MembershipRepositoryImpl(MembershipDao dao) {
    this.dao = dao;
  }

  // Thêm thành viên server
  @Override
  public void addServerMember(ServerId serverId, UserId userId, Instant joinedAt) {
    dao.addServerMember(serverId.value(), userId.value(), joinedAt);
  }

  // Xóa một thành viên khỏi server
  @Override
  public void removeServerMember(ServerId serverId, UserId userId) {
    dao.removeServerMember(serverId.value(), userId.value());
  }

  // Xóa mọi thành viên của server
  @Override
  public void removeServer(ServerId serverId) {
    dao.removeServer(serverId.value());
  }

  // Ghi hai participant của kênh DM
  @Override
  public void addDmParticipants(ChannelId channelId, UserId first, UserId second) {
    dao.addDmParticipants(channelId.value(), first.value(), second.value());
  }

  // Kiểm quyền bằng một câu EXISTS trên cả hai bảng
  @Override
  public boolean canAccess(UserId userId, ChannelScope scope) {
    return dao.canAccess(
        userId.value(),
        scope.id().value(),
        scope.serverId() == null ? null : scope.serverId().value());
  }
}

package com.nexus.community.infrastructure.persistence.channel;

import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelOverwrite;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

// Cài đặt ChannelOverwriteRepository bằng OverwriteDao
@Repository
class ChannelOverwriteRepositoryImpl implements ChannelOverwriteRepository {

  private final OverwriteDao dao;

  // Nhận DAO qua constructor
  ChannelOverwriteRepositoryImpl(OverwriteDao dao) {
    this.dao = dao;
  }

  // Overwrite của một kênh
  @Override
  public List<ChannelOverwrite> findByChannel(ChannelId channelId) {
    return dao.findByIdChannelId(channelId.value()).stream()
        .map(ChannelOverwriteRepositoryImpl::toDomain)
        .toList();
  }

  // Overwrite của cả server bằng một query rồi gom theo kênh
  @Override
  public Map<ChannelId, List<ChannelOverwrite>> findByServer(ServerId serverId) {
    return dao.findAllByServerId(serverId.value()).stream()
        .collect(
            Collectors.groupingBy(
                e -> new ChannelId(e.id.channelId()),
                Collectors.mapping(ChannelOverwriteRepositoryImpl::toDomain, Collectors.toList())));
  }

  // Ghi bằng INSERT ON CONFLICT DO UPDATE
  @Override
  public void upsert(ChannelId channelId, ChannelOverwrite overwrite) {
    dao.upsert(
        channelId.value(),
        overwrite.type().name(),
        overwrite.targetId(),
        overwrite.allow().bits(),
        overwrite.deny().bits());
  }

  // Gỡ theo khóa ghép, true khi có đúng một dòng bị xóa
  @Override
  public boolean remove(ChannelId channelId, OverwriteTarget type, UUID targetId) {
    return dao.deleteOne(channelId.value(), type.name(), targetId) > 0;
  }

  // Xóa mọi overwrite của đích trong server bằng một câu DELETE
  @Override
  public void removeByTarget(ServerId serverId, UUID targetId) {
    dao.deleteByTarget(serverId.value(), targetId);
  }

  // Đổi hàng lưu trữ thành giá trị miền
  private static ChannelOverwrite toDomain(OverwriteJpaEntity e) {
    return new ChannelOverwrite(
        OverwriteTarget.valueOf(e.id.targetType()),
        e.id.targetId(),
        new PermissionSet(e.allow),
        new PermissionSet(e.deny));
  }
}

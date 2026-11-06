package com.nexus.community.infrastructure.persistence.channel;

import com.nexus.community.domain.channel.Channel;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelRepository;
import com.nexus.community.domain.server.ServerId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// Cài đặt ChannelRepository bằng ChannelDao và ChannelMapper
@Repository
class ChannelRepositoryImpl implements ChannelRepository {

  private final ChannelDao dao;
  private final ChannelMapper mapper;

  // Nhận DAO và mapper qua constructor
  ChannelRepositoryImpl(ChannelDao dao, ChannelMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay; kênh chưa có hàng thì persist (version null), ngược lại merge theo version
  @Override
  public void save(Channel channel) {
    ChannelJpaEntity entity = mapper.toJpa(channel);
    if (!dao.existsById(entity.id)) {
      entity.version = null;
    }
    dao.saveAndFlush(entity);
  }

  // Tìm kênh theo id
  @Override
  public Optional<Channel> findById(ChannelId id) {
    return dao.findById(id.value()).map(mapper::toDomain);
  }

  // Liệt kê kênh của server theo position rồi id
  @Override
  public List<Channel> findAllByServer(ServerId serverId) {
    return dao.findAllByServerIdOrderByPositionAscIdAsc(serverId.value()).stream()
        .map(mapper::toDomain)
        .toList();
  }

  // Xóa kênh theo id
  @Override
  public void delete(ChannelId id) {
    dao.deleteById(id.value());
  }
}

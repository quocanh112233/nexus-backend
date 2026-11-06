package com.nexus.community.infrastructure.persistence.channel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data DAO cho bảng channels
interface ChannelDao extends JpaRepository<ChannelJpaEntity, UUID> {

  // Kênh của server, sắp theo position rồi id
  List<ChannelJpaEntity> findAllByServerIdOrderByPositionAscIdAsc(UUID serverId);
}

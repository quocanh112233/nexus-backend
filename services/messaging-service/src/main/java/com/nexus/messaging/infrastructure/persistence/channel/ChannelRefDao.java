package com.nexus.messaging.infrastructure.persistence.channel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng channel_refs, chỉ dùng lệnh INSERT native
interface ChannelRefDao extends JpaRepository<ChannelRefJpaEntity, UUID> {

  // Thêm kênh server nếu chưa có, chạy trong transaction của nơi gọi
  @Modifying
  @Query(
      value =
          "insert into {h-schema}channel_refs (channel_id, kind, server_id, updated_at)"
              + " values (:channelId, 'SERVER', :serverId, now())"
              + " on conflict (channel_id) do nothing",
      nativeQuery = true)
  void upsertServerChannel(@Param("channelId") UUID channelId, @Param("serverId") UUID serverId);
}

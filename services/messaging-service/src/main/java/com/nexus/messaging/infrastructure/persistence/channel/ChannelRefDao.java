package com.nexus.messaging.infrastructure.persistence.channel;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng channel_refs, chỉ dùng lệnh native
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

  // Tạo dòng đã xóa hoặc đánh dấu xóa dòng có sẵn, giữ deleted_at đầu tiên
  @Modifying
  @Query(
      value =
          "insert into {h-schema}channel_refs (channel_id, kind, server_id, deleted_at, updated_at)"
              + " values (:channelId, 'SERVER', :serverId, :at, now())"
              + " on conflict (channel_id) do update set"
              + " deleted_at = coalesce(channel_refs.deleted_at, excluded.deleted_at),"
              + " updated_at = now()",
      nativeQuery = true)
  void tombstoneChannel(
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId,
      @Param("at") Instant at);

  // Đánh dấu xóa mọi kênh của server chưa bị xóa
  @Modifying
  @Query(
      value =
          "update {h-schema}channel_refs set deleted_at = coalesce(deleted_at, :at),"
              + " updated_at = now() where server_id = :serverId",
      nativeQuery = true)
  void tombstoneServer(@Param("serverId") UUID serverId, @Param("at") Instant at);

  // True nếu kênh có dòng và deleted_at khác null
  @Query(
      value =
          "select exists (select 1 from {h-schema}channel_refs"
              + " where channel_id = :channelId and deleted_at is not null)",
      nativeQuery = true)
  boolean isDeleted(@Param("channelId") UUID channelId);
}

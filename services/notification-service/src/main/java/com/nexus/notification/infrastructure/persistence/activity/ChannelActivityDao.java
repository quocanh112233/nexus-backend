package com.nexus.notification.infrastructure.persistence.activity;

import com.nexus.notification.infrastructure.persistence.readstate.ReadStateJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng channel_activity, chỉ dùng lệnh native
interface ChannelActivityDao extends Repository<ReadStateJpaEntity, UUID> {

  // Thêm kênh nếu chưa có, không đụng dòng có sẵn kể cả tombstone
  @Modifying
  @Query(
      value =
          "insert into {h-schema}channel_activity (channel_id, server_id)"
              + " values (:channelId, cast(:serverId as uuid))"
              + " on conflict (channel_id) do nothing",
      nativeQuery = true)
  void upsertChannel(@Param("channelId") UUID channelId, @Param("serverId") UUID serverId);

  // Tạo dòng đã xóa hoặc đánh dấu xóa dòng có sẵn, giữ deleted_at và server_id đầu tiên
  @Modifying
  @Query(
      value =
          "insert into {h-schema}channel_activity (channel_id, server_id, deleted_at)"
              + " values (:channelId, cast(:serverId as uuid), :at)"
              + " on conflict (channel_id) do update set"
              + " deleted_at = coalesce(channel_activity.deleted_at, excluded.deleted_at),"
              + " server_id = coalesce(channel_activity.server_id, excluded.server_id)",
      nativeQuery = true)
  void tombstoneChannel(
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId,
      @Param("at") Instant at);

  // Đánh dấu xóa mọi kênh đã biết của server, giữ deleted_at đầu tiên
  @Modifying
  @Query(
      value =
          "update {h-schema}channel_activity set deleted_at = coalesce(deleted_at, :at)"
              + " where server_id = :serverId",
      nativeQuery = true)
  void tombstoneServer(@Param("serverId") UUID serverId, @Param("at") Instant at);

  // Ghi tin mới nhất theo GREATEST của uuid, so sánh byte ở Postgres, tự tạo kênh nếu chưa có
  @Modifying
  @Query(
      value =
          "insert into {h-schema}channel_activity"
              + " (channel_id, server_id, last_message_id, last_message_at)"
              + " values (:channelId, cast(:serverId as uuid), :messageId, :at)"
              + " on conflict (channel_id) do update set"
              + " last_message_id = greatest(channel_activity.last_message_id,"
              + " excluded.last_message_id),"
              + " last_message_at = case when excluded.last_message_id > coalesce("
              + "channel_activity.last_message_id,"
              + " cast('00000000-0000-0000-0000-000000000000' as uuid))"
              + " then excluded.last_message_at else channel_activity.last_message_at end,"
              + " server_id = coalesce(channel_activity.server_id, excluded.server_id)",
      nativeQuery = true)
  void recordMessage(
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId,
      @Param("messageId") UUID messageId,
      @Param("at") Instant at);

  // Trả [channel_id, server_id] của kênh chưa bị xóa
  @Query(
      value =
          "select channel_id, server_id from {h-schema}channel_activity"
              + " where channel_id = :channelId and deleted_at is null",
      nativeQuery = true)
  List<Object[]> findLive(@Param("channelId") UUID channelId);
}

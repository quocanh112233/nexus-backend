package com.nexus.community.infrastructure.persistence.channel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng channel_permission_overwrites, lệnh ghi cần transaction của nơi gọi
interface OverwriteDao extends JpaRepository<OverwriteJpaEntity, OverwriteKey> {

  // Overwrite của một kênh
  List<OverwriteJpaEntity> findByIdChannelId(UUID channelId);

  // Overwrite của mọi kênh trong server bằng một query
  @Query(
      "select o from OverwriteJpaEntity o where o.id.channelId in"
          + " (select c.id from ChannelJpaEntity c where c.serverId = :serverId)")
  List<OverwriteJpaEntity> findAllByServerId(@Param("serverId") UUID serverId);

  // Ghi overwrite, đã có cùng khóa thì ghi đè allow và deny
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "insert into {h-schema}channel_permission_overwrites"
              + " (channel_id, target_type, target_id, allow, deny)"
              + " values (:channelId, :type, :targetId, :allow, :deny)"
              + " on conflict (channel_id, target_type, target_id)"
              + " do update set allow = excluded.allow, deny = excluded.deny",
      nativeQuery = true)
  int upsert(
      @Param("channelId") UUID channelId,
      @Param("type") String type,
      @Param("targetId") UUID targetId,
      @Param("allow") long allow,
      @Param("deny") long deny);

  // Gỡ overwrite theo khóa, trả số dòng bị xóa
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "delete from {h-schema}channel_permission_overwrites where channel_id = :channelId"
              + " and target_type = :type and target_id = :targetId",
      nativeQuery = true)
  int deleteOne(
      @Param("channelId") UUID channelId,
      @Param("type") String type,
      @Param("targetId") UUID targetId);

  // Xóa mọi overwrite của một đích trong các kênh của server
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "delete from {h-schema}channel_permission_overwrites where target_id = :targetId"
              + " and channel_id in (select id from {h-schema}channels where server_id = :serverId)",
      nativeQuery = true)
  int deleteByTarget(@Param("serverId") UUID serverId, @Param("targetId") UUID targetId);
}

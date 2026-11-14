package com.nexus.notification.infrastructure.persistence.mention;

import com.nexus.notification.infrastructure.persistence.readstate.ReadStateJpaEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng mentions, chỉ dùng lệnh native trả số dòng bị ảnh hưởng
interface MentionDao extends Repository<ReadStateJpaEntity, UUID> {

  // Chèn nếu chưa có, trả 1 khi chèn thật và 0 khi trùng (message, user)
  @Modifying
  @Query(
      value =
          "insert into {h-schema}mentions"
              + " (message_id, user_id, channel_id, server_id, author_id, created_at)"
              + " values (:messageId, :userId, :channelId, cast(:serverId as uuid),"
              + " :authorId, :createdAt)"
              + " on conflict (message_id, user_id) do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("messageId") UUID messageId,
      @Param("userId") UUID userId,
      @Param("channelId") UUID channelId,
      @Param("serverId") UUID serverId,
      @Param("authorId") UUID authorId,
      @Param("createdAt") Instant createdAt);

  // Xóa mention của user trong server
  @Modifying
  @Query(
      value = "delete from {h-schema}mentions where user_id = :userId and server_id = :serverId",
      nativeQuery = true)
  void deleteForUserInServer(@Param("userId") UUID userId, @Param("serverId") UUID serverId);

  // Xóa mention của mọi user trong server
  @Modifying
  @Query(value = "delete from {h-schema}mentions where server_id = :serverId", nativeQuery = true)
  void deleteForServer(@Param("serverId") UUID serverId);
}

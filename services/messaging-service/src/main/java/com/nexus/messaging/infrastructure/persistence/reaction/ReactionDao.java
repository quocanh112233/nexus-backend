package com.nexus.messaging.infrastructure.persistence.reaction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng reactions, chỉ dùng lệnh native trả số dòng bị ảnh hưởng
interface ReactionDao extends JpaRepository<ReactionJpaEntity, ReactionKey> {

  // Chèn nếu chưa có, trả 1 khi chèn thật và 0 khi trùng
  @Modifying
  @Query(
      value =
          "insert into {h-schema}reactions (message_id, user_id, emoji, created_at)"
              + " values (:messageId, :userId, :emoji, :createdAt)"
              + " on conflict (message_id, user_id, emoji) do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("messageId") UUID messageId,
      @Param("userId") UUID userId,
      @Param("emoji") String emoji,
      @Param("createdAt") Instant createdAt);

  // Xóa đúng một reaction, trả số dòng bị xóa
  @Modifying
  @Query(
      value =
          "delete from {h-schema}reactions"
              + " where message_id = :messageId and user_id = :userId and emoji = :emoji",
      nativeQuery = true)
  int deleteOne(
      @Param("messageId") UUID messageId,
      @Param("userId") UUID userId,
      @Param("emoji") String emoji);

  // Gom reaction nhiều tin thành [message_id, emoji, số lượt, viewer đã thả] theo lượt thả đầu
  @Query(
      value =
          "select message_id, emoji, count(*), bool_or(user_id = :viewer)"
              + " from {h-schema}reactions where message_id in (:messageIds)"
              + " group by message_id, emoji order by message_id, min(created_at), emoji",
      nativeQuery = true)
  List<Object[]> summarize(
      @Param("messageIds") Collection<UUID> messageIds, @Param("viewer") UUID viewer);
}

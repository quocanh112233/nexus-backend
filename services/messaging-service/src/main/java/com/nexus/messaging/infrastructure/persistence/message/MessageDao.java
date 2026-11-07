package com.nexus.messaging.infrastructure.persistence.message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng messages, kèm ba câu keyset native dùng index (channel_id, id DESC)
interface MessageDao extends JpaRepository<MessageJpaEntity, UUID> {

  String LATEST_SQL =
      "select m.* from {h-schema}messages m where m.channel_id = :channelId"
          + " order by m.id desc limit :limit";
  String BEFORE_SQL =
      "select m.* from {h-schema}messages m where m.channel_id = :channelId and m.id < :before"
          + " order by m.id desc limit :limit";
  String AFTER_SQL =
      "select m.* from {h-schema}messages m where m.channel_id = :channelId and m.id > :after"
          + " order by m.id asc limit :limit";

  // Trang mới nhất của kênh, id giảm dần
  @Query(value = LATEST_SQL, nativeQuery = true)
  List<MessageJpaEntity> latest(@Param("channelId") UUID channelId, @Param("limit") int limit);

  // Trang cũ hơn con trỏ before, id giảm dần
  @Query(value = BEFORE_SQL, nativeQuery = true)
  List<MessageJpaEntity> before(
      @Param("channelId") UUID channelId, @Param("before") UUID before, @Param("limit") int limit);

  // Trang mới hơn con trỏ after, id tăng dần
  @Query(value = AFTER_SQL, nativeQuery = true)
  List<MessageJpaEntity> after(
      @Param("channelId") UUID channelId, @Param("after") UUID after, @Param("limit") int limit);

  // Tìm tin theo tác giả và nonce
  Optional<MessageJpaEntity> findByAuthorIdAndNonce(UUID authorId, String nonce);
}

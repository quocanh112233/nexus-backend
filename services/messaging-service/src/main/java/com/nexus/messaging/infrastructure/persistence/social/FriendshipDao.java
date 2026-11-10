package com.nexus.messaging.infrastructure.persistence.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng friendships, ghi bằng lệnh native trả số dòng bị ảnh hưởng
interface FriendshipDao extends JpaRepository<FriendshipJpaEntity, FriendshipJpaEntity.Key> {

  // Chèn nếu chưa có, trả 1 khi chèn thật và 0 khi trùng
  @Modifying
  @Query(
      value =
          "insert into {h-schema}friendships (user_low_id, user_high_id, created_at)"
              + " values (:low, :high, :createdAt)"
              + " on conflict (user_low_id, user_high_id) do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("low") UUID low, @Param("high") UUID high, @Param("createdAt") Instant createdAt);

  // Xóa đúng một cặp, trả số dòng bị xóa
  @Modifying
  @Query(
      value = "delete from {h-schema}friendships where user_low_id = :low and user_high_id = :high",
      nativeQuery = true)
  int deleteOne(@Param("low") UUID low, @Param("high") UUID high);

  // Id bạn bè của user ở cả hai phía cặp, mỗi nhánh dùng một index
  @Query(
      value =
          "select user_high_id from {h-schema}friendships where user_low_id = :user"
              + " union all select user_low_id from {h-schema}friendships"
              + " where user_high_id = :user",
      nativeQuery = true)
  List<UUID> friendIdsOf(@Param("user") UUID user);
}

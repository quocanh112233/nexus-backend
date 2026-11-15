package com.nexus.messaging.infrastructure.persistence.social;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng dm_channels
interface DmChannelDao extends JpaRepository<DmChannelJpaEntity, UUID> {

  // Chèn nếu cả id lẫn cặp chưa có, trả 1 khi chèn thật và 0 khi trùng
  @Modifying
  @Query(
      value =
          "insert into {h-schema}dm_channels (id, user_low_id, user_high_id, created_at)"
              + " values (:id, :low, :high, :createdAt) on conflict do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("low") UUID low,
      @Param("high") UUID high,
      @Param("createdAt") Instant createdAt);

  // Tìm kênh theo cặp đã chuẩn hóa
  Optional<DmChannelJpaEntity> findByUserLowIdAndUserHighId(UUID userLowId, UUID userHighId);

  // Kênh có user ở một trong hai phía, mới nhất trước, trùng giờ thì id lớn trước
  List<DmChannelJpaEntity> findByUserLowIdOrUserHighIdOrderByCreatedAtDescIdDesc(
      UUID userLowId, UUID userHighId);

  // Lô kênh DM có id lớn hơn after theo id tăng dần
  @Query(
      value = "select * from {h-schema}dm_channels where id > :after order by id limit :limit",
      nativeQuery = true)
  List<DmChannelJpaEntity> pageAfter(@Param("after") UUID after, @Param("limit") int limit);
}

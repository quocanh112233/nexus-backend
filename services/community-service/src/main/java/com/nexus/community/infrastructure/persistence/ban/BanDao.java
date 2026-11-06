package com.nexus.community.infrastructure.persistence.ban;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng server_bans, lệnh ghi chạy trong transaction của nơi gọi
interface BanDao extends JpaRepository<BanJpaEntity, BanKey> {

  // Thêm ban nếu chưa có, trả số dòng được thêm (0 hoặc 1)
  @Modifying
  @Query(
      value =
          "insert into {h-schema}server_bans (server_id, user_id, banned_by, reason, created_at)"
              + " values (:serverId, :userId, :bannedBy, :reason, :createdAt)"
              + " on conflict do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("serverId") UUID serverId,
      @Param("userId") UUID userId,
      @Param("bannedBy") UUID bannedBy,
      @Param("reason") String reason,
      @Param("createdAt") Instant createdAt);

  // Xóa ban theo khóa ghép, trả số dòng bị xóa (0 hoặc 1)
  @Modifying
  @Query(
      value = "delete from {h-schema}server_bans where server_id = :serverId and user_id = :userId",
      nativeQuery = true)
  int deleteByKey(@Param("serverId") UUID serverId, @Param("userId") UUID userId);

  // Trang đầu ban của server theo user_id tăng dần
  List<BanJpaEntity> findByIdServerIdOrderByIdUserIdAsc(UUID serverId, Pageable pageable);

  // Trang kế tiếp gồm ban có user_id lớn hơn after
  List<BanJpaEntity> findByIdServerIdAndIdUserIdGreaterThanOrderByIdUserIdAsc(
      UUID serverId, UUID after, Pageable pageable);
}

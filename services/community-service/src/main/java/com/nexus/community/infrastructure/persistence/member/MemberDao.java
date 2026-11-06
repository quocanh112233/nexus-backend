package com.nexus.community.infrastructure.persistence.member;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng members, lệnh INSERT chạy trong transaction của nơi gọi
interface MemberDao extends JpaRepository<MemberJpaEntity, MemberKey> {

  // Thêm thành viên nếu chưa có, trả số dòng được thêm (0 hoặc 1)
  @Modifying
  @Query(
      value =
          "insert into {h-schema}members (server_id, user_id, joined_at)"
              + " values (:serverId, :userId, :joinedAt) on conflict do nothing",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("serverId") UUID serverId,
      @Param("userId") UUID userId,
      @Param("joinedAt") Instant joinedAt);

  // Đếm thành viên của server
  long countByIdServerId(UUID serverId);

  // Trang đầu thành viên của server theo user_id tăng dần
  List<MemberJpaEntity> findByIdServerIdOrderByIdUserIdAsc(UUID serverId, Pageable pageable);

  // Trang kế tiếp gồm thành viên có user_id lớn hơn after
  List<MemberJpaEntity> findByIdServerIdAndIdUserIdGreaterThanOrderByIdUserIdAsc(
      UUID serverId, UUID after, Pageable pageable);
}

package com.nexus.community.infrastructure.persistence.invite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng invites, lệnh UPDATE chạy trong transaction của nơi gọi
interface InviteDao extends JpaRepository<InviteJpaEntity, String> {

  // Lời mời của server, mới tạo trước
  List<InviteJpaEntity> findAllByServerIdOrderByCreatedAtDescCodeAsc(UUID serverId);

  // Tăng uses nếu lời mời còn dùng được, trả số dòng bị đổi (0 hoặc 1)
  @Modifying
  @Query(
      value =
          "update {h-schema}invites set uses = uses + 1 where code = :code"
              + " and revoked_at is null and expires_at > :now"
              + " and (max_uses = 0 or uses < max_uses)",
      nativeQuery = true)
  int tryConsume(@Param("code") String code, @Param("now") Instant now);
}

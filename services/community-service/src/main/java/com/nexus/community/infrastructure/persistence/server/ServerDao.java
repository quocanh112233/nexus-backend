package com.nexus.community.infrastructure.persistence.server;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng servers
interface ServerDao extends JpaRepository<ServerJpaEntity, UUID> {

  // Server mà user là thành viên, sắp theo created_at rồi id
  @Query(
      value =
          "select s.* from {h-schema}servers s join {h-schema}members m on m.server_id = s.id"
              + " where m.user_id = :userId order by s.created_at, s.id",
      nativeQuery = true)
  List<ServerJpaEntity> findAllByMember(@Param("userId") UUID userId);
}

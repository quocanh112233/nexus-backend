package com.nexus.community.infrastructure.persistence.server;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data DAO cho bảng roles
interface RoleDao extends JpaRepository<RoleJpaEntity, UUID> {

  // Lấy role của nhiều server cùng lúc
  List<RoleJpaEntity> findAllByServerIdIn(Collection<UUID> serverIds);
}

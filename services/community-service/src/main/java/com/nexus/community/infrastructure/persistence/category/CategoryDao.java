package com.nexus.community.infrastructure.persistence.category;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data DAO cho bảng categories
interface CategoryDao extends JpaRepository<CategoryJpaEntity, UUID> {

  // Category của server, sắp theo position rồi id
  List<CategoryJpaEntity> findAllByServerIdOrderByPositionAscIdAsc(UUID serverId);
}

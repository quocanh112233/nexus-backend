package com.nexus.community.infrastructure.persistence.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng servers; version null nghĩa là bản ghi mới nên save sẽ persist
@Entity
@Table(name = "servers")
class ServerJpaEntity {

  @Id UUID id;

  @Column(nullable = false)
  String name;

  @Column(name = "owner_id", nullable = false)
  UUID ownerId;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version Long version;

  // Constructor mặc định cho JPA
  protected ServerJpaEntity() {}
}

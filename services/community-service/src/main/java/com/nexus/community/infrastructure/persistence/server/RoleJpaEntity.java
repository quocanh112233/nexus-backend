package com.nexus.community.infrastructure.persistence.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng roles, khóa lạc quan do hàng server giữ nên không có version
@Entity
@Table(name = "roles")
class RoleJpaEntity {

  @Id UUID id;

  @Column(name = "server_id", nullable = false)
  UUID serverId;

  @Column(nullable = false)
  String name;

  @Column(nullable = false)
  int color;

  @Column(nullable = false)
  long permissions;

  @Column(nullable = false)
  int position;

  @Column(name = "is_default", nullable = false)
  boolean isDefault;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected RoleJpaEntity() {}
}

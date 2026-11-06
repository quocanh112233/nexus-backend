package com.nexus.community.infrastructure.persistence.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng categories, không có cột version hay updated_at
@Entity
@Table(name = "categories")
class CategoryJpaEntity {

  @Id UUID id;

  @Column(name = "server_id", nullable = false)
  UUID serverId;

  @Column(nullable = false)
  String name;

  @Column(nullable = false)
  int position;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected CategoryJpaEntity() {}
}

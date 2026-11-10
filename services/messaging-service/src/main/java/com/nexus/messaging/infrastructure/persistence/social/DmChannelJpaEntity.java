package com.nexus.messaging.infrastructure.persistence.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng dm_channels, chỉ đọc qua JPA còn ghi bằng lệnh native
@Entity
@Table(name = "dm_channels")
class DmChannelJpaEntity {

  @Id UUID id;

  @Column(name = "user_low_id", nullable = false)
  UUID userLowId;

  @Column(name = "user_high_id", nullable = false)
  UUID userHighId;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected DmChannelJpaEntity() {}
}

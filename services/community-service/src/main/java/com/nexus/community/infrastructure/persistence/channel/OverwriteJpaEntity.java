package com.nexus.community.infrastructure.persistence.channel;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// Ánh xạ bảng channel_permission_overwrites, chỉ dùng để đọc vì ghi đi qua lệnh native
@Entity
@Table(name = "channel_permission_overwrites")
class OverwriteJpaEntity {

  @EmbeddedId OverwriteKey id;

  @Column(name = "allow", nullable = false)
  long allow;

  @Column(name = "deny", nullable = false)
  long deny;

  // Constructor mặc định cho JPA
  protected OverwriteJpaEntity() {}
}

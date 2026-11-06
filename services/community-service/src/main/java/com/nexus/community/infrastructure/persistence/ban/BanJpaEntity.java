package com.nexus.community.infrastructure.persistence.ban;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng server_bans, chỉ dùng để đọc vì thêm và xóa đi qua SQL native
@Entity
@Table(name = "server_bans")
class BanJpaEntity {

  @EmbeddedId BanKey id;

  @Column(name = "banned_by", nullable = false)
  UUID bannedBy;

  @Column(name = "reason")
  String reason;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected BanJpaEntity() {}
}

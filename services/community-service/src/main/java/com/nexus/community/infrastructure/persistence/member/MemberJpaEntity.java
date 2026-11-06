package com.nexus.community.infrastructure.persistence.member;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

// Ánh xạ bảng members, chỉ dùng để đọc và xóa vì thêm đi qua INSERT ON CONFLICT
@Entity
@Table(name = "members")
class MemberJpaEntity {

  @EmbeddedId MemberKey id;

  @Column(name = "joined_at", nullable = false)
  Instant joinedAt;

  // Constructor mặc định cho JPA
  protected MemberJpaEntity() {}
}

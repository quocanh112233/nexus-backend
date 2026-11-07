package com.nexus.messaging.infrastructure.persistence.reaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ tối thiểu bảng reactions để Spring Data dựng được DAO
@Entity
@Table(name = "reactions")
@IdClass(ReactionKey.class)
class ReactionJpaEntity {

  @Id
  @Column(name = "message_id")
  UUID messageId;

  @Id
  @Column(name = "user_id")
  UUID userId;

  @Id String emoji;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected ReactionJpaEntity() {}
}

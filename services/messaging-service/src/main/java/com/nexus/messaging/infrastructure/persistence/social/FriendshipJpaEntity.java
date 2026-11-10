package com.nexus.messaging.infrastructure.persistence.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ tối thiểu bảng friendships để Spring Data dựng được DAO
@Entity
@Table(name = "friendships")
@IdClass(FriendshipJpaEntity.Key.class)
class FriendshipJpaEntity {

  @Id
  @Column(name = "user_low_id")
  UUID userLowId;

  @Id
  @Column(name = "user_high_id")
  UUID userHighId;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  // Constructor mặc định cho JPA
  protected FriendshipJpaEntity() {}

  // Khóa chính ghép (user_low_id, user_high_id)
  record Key(UUID userLowId, UUID userHighId) implements Serializable {}
}

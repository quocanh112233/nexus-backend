package com.nexus.messaging.infrastructure.persistence.social;

import com.nexus.messaging.domain.social.FriendRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng friend_requests, @Version là nguồn sự thật của khóa lạc quan
@Entity
@Table(name = "friend_requests")
class FriendRequestJpaEntity {

  @Id UUID id;

  @Column(name = "requester_id", nullable = false)
  UUID requesterId;

  @Column(name = "addressee_id", nullable = false)
  UUID addresseeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  FriendRequestStatus status;

  @Version Long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "responded_at")
  Instant respondedAt;

  // Constructor mặc định cho JPA
  protected FriendRequestJpaEntity() {}
}

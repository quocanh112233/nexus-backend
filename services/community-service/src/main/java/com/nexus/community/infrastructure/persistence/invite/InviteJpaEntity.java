package com.nexus.community.infrastructure.persistence.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

// Ánh xạ bảng invites; chỉ revoked_at được UPDATE qua JPA, uses chỉ tăng qua tryConsume
@Entity
@Table(name = "invites")
class InviteJpaEntity implements Persistable<String> {

  @Id String code;

  @Column(name = "server_id", nullable = false, updatable = false)
  UUID serverId;

  @Column(name = "inviter_id", nullable = false, updatable = false)
  UUID inviterId;

  @Column(name = "max_uses", nullable = false, updatable = false)
  int maxUses;

  @Column(nullable = false, updatable = false)
  int uses;

  @Column(name = "expires_at", updatable = false)
  Instant expiresAt;

  @Column(name = "revoked_at")
  Instant revokedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Transient boolean fresh;

  // Constructor mặc định cho JPA
  protected InviteJpaEntity() {}

  // Khóa chính là mã mời
  @Override
  public String getId() {
    return code;
  }

  // True khi là lời mời mới để Spring Data persist (INSERT) thay vì merge đè hàng cùng mã
  @Override
  public boolean isNew() {
    return fresh;
  }
}

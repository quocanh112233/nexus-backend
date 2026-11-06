package com.nexus.community.infrastructure.persistence.member;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

// Ánh xạ bảng member_roles, chỉ dùng để đọc vì ghi đi qua lệnh native
@Entity
@Table(name = "member_roles")
class MemberRoleJpaEntity {

  @EmbeddedId Key id;

  // Constructor mặc định cho JPA
  protected MemberRoleJpaEntity() {}

  // Khóa chính ghép (server_id, user_id, role_id) của bảng member_roles
  @Embeddable
  record Key(
      @Column(name = "server_id") UUID serverId,
      @Column(name = "user_id") UUID userId,
      @Column(name = "role_id") UUID roleId)
      implements Serializable {}
}

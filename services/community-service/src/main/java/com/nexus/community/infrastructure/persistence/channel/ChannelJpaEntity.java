package com.nexus.community.infrastructure.persistence.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng channels; version null nghĩa là bản ghi mới nên save sẽ persist
@Entity
@Table(name = "channels")
class ChannelJpaEntity {

  @Id UUID id;

  @Column(name = "server_id", nullable = false)
  UUID serverId;

  @Column(name = "category_id")
  UUID categoryId;

  @Column(nullable = false)
  String name;

  String topic;

  @Column(nullable = false)
  String type;

  @Column(nullable = false)
  int position;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version Long version;

  // Constructor mặc định cho JPA
  protected ChannelJpaEntity() {}
}

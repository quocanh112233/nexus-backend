package com.nexus.messaging.infrastructure.persistence.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// Ánh xạ tối thiểu bảng channel_refs để Spring Data dựng được DAO
@Entity
@Table(name = "channel_refs")
class ChannelRefJpaEntity {

  @Id
  @Column(name = "channel_id")
  UUID channelId;

  // Constructor mặc định cho JPA
  protected ChannelRefJpaEntity() {}
}

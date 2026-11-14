package com.nexus.notification.infrastructure.persistence.readstate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// Ánh xạ tối thiểu bảng read_states để Spring Data dựng các DAO native, không dùng để đọc ghi
@Entity
@Table(name = "read_states")
public class ReadStateJpaEntity {

  @Id
  @Column(name = "user_id")
  UUID userId;

  // Constructor mặc định cho JPA
  protected ReadStateJpaEntity() {}
}

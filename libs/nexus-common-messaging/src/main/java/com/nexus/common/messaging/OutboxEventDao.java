package com.nexus.common.messaging;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// Repository Spring Data cho bảng outbox_event
public interface OutboxEventDao extends JpaRepository<OutboxEventJpaEntity, UUID> {}

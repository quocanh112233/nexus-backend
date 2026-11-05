package com.nexus.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

// Ánh xạ bảng outbox_event; Persistable để save() luôn INSERT, không SELECT trước
@Entity
@Table(name = "outbox_event")
public class OutboxEventJpaEntity implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "event_version", nullable = false)
  private int eventVersion;

  @Column(name = "routing_key", nullable = false)
  private String routingKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> headers;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  @Transient private boolean isNew = true;

  protected OutboxEventJpaEntity() {}

  // Tạo dòng mới chưa publish, attempts bằng 0
  public OutboxEventJpaEntity(
      UUID id, OutboxMessage message, Map<String, Object> headers, Instant createdAt) {
    this.id = id;
    this.aggregateType = message.aggregateType();
    this.aggregateId = message.aggregateId();
    this.eventType = message.eventType();
    this.eventVersion = message.eventVersion();
    this.routingKey = message.routingKey();
    this.payload = message.payload();
    this.headers = headers;
    this.createdAt = createdAt;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  // Đánh dấu entity đã tồn tại trong DB sau khi lưu hoặc nạp
  @PostPersist
  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public int getEventVersion() {
    return eventVersion;
  }

  public String getRoutingKey() {
    return routingKey;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public Map<String, Object> getHeaders() {
    return headers;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttempts() {
    return attempts;
  }
}

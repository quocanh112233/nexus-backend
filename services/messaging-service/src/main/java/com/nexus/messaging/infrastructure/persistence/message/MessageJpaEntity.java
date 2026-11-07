package com.nexus.messaging.infrastructure.persistence.message;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

// Ánh xạ bảng messages cùng mention (đọc theo userId, không lưu thứ tự) và attachment
@Entity
@Table(name = "messages")
class MessageJpaEntity {

  @Id UUID id;

  @Column(name = "channel_id", nullable = false)
  UUID channelId;

  @Column(name = "author_id", nullable = false)
  UUID authorId;

  @Column(nullable = false)
  String content;

  @Column(name = "reply_to_id")
  UUID replyToId;

  String nonce;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "edited_at")
  Instant editedAt;

  @Column(name = "deleted_at")
  Instant deletedAt;

  @Version Long version;

  @ElementCollection(fetch = FetchType.EAGER)
  @BatchSize(size = 100)
  @OrderBy
  @CollectionTable(name = "message_mentions", joinColumns = @JoinColumn(name = "message_id"))
  @Column(name = "user_id")
  Set<UUID> mentions = new LinkedHashSet<>();

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
  @JoinColumn(name = "message_id", nullable = false)
  @BatchSize(size = 100)
  @OrderBy("id")
  List<AttachmentJpaEntity> attachments = new ArrayList<>();

  // Constructor mặc định cho JPA
  protected MessageJpaEntity() {}
}

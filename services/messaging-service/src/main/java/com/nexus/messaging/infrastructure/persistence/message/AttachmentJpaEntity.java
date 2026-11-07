package com.nexus.messaging.infrastructure.persistence.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// Ánh xạ bảng message_attachments, message_id do MessageJpaEntity điền qua @JoinColumn
@Entity
@Table(name = "message_attachments")
class AttachmentJpaEntity {

  @Id UUID id;

  @Column(name = "public_id", nullable = false)
  String publicId;

  @Column(nullable = false)
  String url;

  @Column(name = "content_type", nullable = false)
  String contentType;

  @Column(nullable = false)
  long bytes;

  @Column(name = "original_filename", nullable = false)
  String originalFilename;

  // Constructor mặc định cho JPA
  protected AttachmentJpaEntity() {}
}

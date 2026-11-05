package com.nexus.identity.infrastructure.persistence.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Ánh xạ bảng refresh_tokens
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenJpaEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false)
  private String tokenHash;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_id")
  private UUID replacedById;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "user_agent")
  private String userAgent;

  private String ip;

  // Constructor mặc định cho JPA
  protected RefreshTokenJpaEntity() {}

  // Id token
  UUID getId() {
    return id;
  }

  // Gán id
  void setId(UUID id) {
    this.id = id;
  }

  // Id chủ sở hữu
  UUID getUserId() {
    return userId;
  }

  // Gán id chủ sở hữu
  void setUserId(UUID userId) {
    this.userId = userId;
  }

  // Chuỗi băm token
  String getTokenHash() {
    return tokenHash;
  }

  // Gán chuỗi băm token
  void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  // Id family
  UUID getFamilyId() {
    return familyId;
  }

  // Gán id family
  void setFamilyId(UUID familyId) {
    this.familyId = familyId;
  }

  // Thời điểm hết hạn
  Instant getExpiresAt() {
    return expiresAt;
  }

  // Gán thời điểm hết hạn
  void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  // Thời điểm thu hồi
  Instant getRevokedAt() {
    return revokedAt;
  }

  // Gán thời điểm thu hồi
  void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  // Id token thay thế
  UUID getReplacedById() {
    return replacedById;
  }

  // Gán id token thay thế
  void setReplacedById(UUID replacedById) {
    this.replacedById = replacedById;
  }

  // Thời điểm tạo
  Instant getCreatedAt() {
    return createdAt;
  }

  // Gán thời điểm tạo
  void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  // User-Agent lúc phát
  String getUserAgent() {
    return userAgent;
  }

  // Gán User-Agent
  void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  // Địa chỉ IP lúc phát
  String getIp() {
    return ip;
  }

  // Gán địa chỉ IP
  void setIp(String ip) {
    this.ip = ip;
  }
}

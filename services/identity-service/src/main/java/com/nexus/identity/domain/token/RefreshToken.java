package com.nexus.identity.domain.token;

import com.nexus.identity.domain.user.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Refresh token bất biến thuộc một family xoay vòng, việc thu hồi đi qua repository để nguyên tử
public final class RefreshToken {

  private final UUID id;
  private final UserId userId;
  private final UUID familyId;
  private final TokenHash tokenHash;
  private final Instant expiresAt;
  private final Instant revokedAt;
  private final UUID replacedById;
  private final Instant createdAt;
  private final String userAgent;
  private final String ip;

  // Khởi tạo nội bộ, chỉ gọi qua issue hoặc rehydrate
  private RefreshToken(
      UUID id,
      UserId userId,
      UUID familyId,
      TokenHash tokenHash,
      Instant expiresAt,
      Instant revokedAt,
      UUID replacedById,
      Instant createdAt,
      String userAgent,
      String ip) {
    this.id = id;
    this.userId = userId;
    this.familyId = familyId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.revokedAt = revokedAt;
    this.replacedById = replacedById;
    this.createdAt = createdAt;
    this.userAgent = userAgent;
    this.ip = ip;
  }

  // Phát token mới chưa thu hồi, hết hạn sau ttl kể từ now
  public static RefreshToken issue(
      UUID id,
      UserId userId,
      UUID familyId,
      TokenHash hash,
      Instant now,
      Duration ttl,
      String userAgent,
      String ip) {
    return new RefreshToken(
        id, userId, familyId, hash, now.plus(ttl), null, null, now, userAgent, ip);
  }

  // Dựng lại token từ lưu trữ
  public static RefreshToken rehydrate(
      UUID id,
      UserId userId,
      UUID familyId,
      TokenHash hash,
      Instant expiresAt,
      Instant revokedAt,
      UUID replacedById,
      Instant createdAt,
      String userAgent,
      String ip) {
    return new RefreshToken(
        id, userId, familyId, hash, expiresAt, revokedAt, replacedById, createdAt, userAgent, ip);
  }

  // Id token
  public UUID id() {
    return id;
  }

  // Chủ sở hữu token
  public UserId userId() {
    return userId;
  }

  // Family xoay vòng của token
  public UUID familyId() {
    return familyId;
  }

  // Chuỗi băm của token thô
  public TokenHash tokenHash() {
    return tokenHash;
  }

  // Thời điểm hết hạn
  public Instant expiresAt() {
    return expiresAt;
  }

  // Thời điểm thu hồi, null nếu còn hoạt động
  public Instant revokedAt() {
    return revokedAt;
  }

  // Id token thay thế sau khi xoay vòng, null nếu chưa xoay
  public UUID replacedById() {
    return replacedById;
  }

  // Thời điểm phát
  public Instant createdAt() {
    return createdAt;
  }

  // User-Agent lúc phát, có thể null
  public String userAgent() {
    return userAgent;
  }

  // Địa chỉ IP lúc phát, có thể null
  public String ip() {
    return ip;
  }

  // Token đã bị thu hồi chưa
  public boolean isRevoked() {
    return revokedAt != null;
  }

  // Token đã quá hạn tại thời điểm now chưa
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}

package com.nexus.community.domain.invite;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;

// Aggregate lời mời vào server, không phát sự kiện miền
public final class Invite {

  private final InviteCode code;
  private final ServerId serverId;
  private final UserId inviterId;
  private final int maxUses;
  private final int uses;
  private final Instant expiresAt;
  private final Instant createdAt;
  private Instant revokedAt;

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private Invite(
      InviteCode code,
      ServerId serverId,
      UserId inviterId,
      int maxUses,
      int uses,
      Instant expiresAt,
      Instant revokedAt,
      Instant createdAt) {
    this.code = code;
    this.serverId = serverId;
    this.inviterId = inviterId;
    this.maxUses = maxUses;
    this.uses = uses;
    this.expiresAt = expiresAt;
    this.revokedAt = revokedAt;
    this.createdAt = createdAt;
  }

  // Tạo lời mời mới, từ chối maxUses âm hoặc hạn không nằm sau now
  public static Invite create(
      InviteCode code,
      ServerId serverId,
      UserId inviterId,
      int maxUses,
      Instant expiresAt,
      Instant now) {
    if (maxUses < 0) {
      throw new DomainValidationException("maxUses must be >= 0");
    }
    if (expiresAt == null || !expiresAt.isAfter(now)) {
      throw new DomainValidationException("expiresAt must be in the future");
    }
    return new Invite(code, serverId, inviterId, maxUses, 0, expiresAt, null, now);
  }

  // Dựng lại lời mời từ lưu trữ
  public static Invite rehydrate(
      InviteCode code,
      ServerId serverId,
      UserId inviterId,
      int maxUses,
      int uses,
      Instant expiresAt,
      Instant revokedAt,
      Instant createdAt) {
    return new Invite(code, serverId, inviterId, maxUses, uses, expiresAt, revokedAt, createdAt);
  }

  // Còn dùng được khi chưa thu hồi, chưa hết hạn và chưa hết lượt
  public boolean isUsable(Instant now) {
    return revokedAt == null
        && (expiresAt == null || now.isBefore(expiresAt))
        && (maxUses == 0 || uses < maxUses);
  }

  // Thu hồi lời mời tại thời điểm now
  public void revoke(Instant now) {
    this.revokedAt = now;
  }

  // Mã mời
  public InviteCode code() {
    return code;
  }

  // Id server
  public ServerId serverId() {
    return serverId;
  }

  // Id người tạo lời mời
  public UserId inviterId() {
    return inviterId;
  }

  // Số lượt tối đa, 0 là không giới hạn
  public int maxUses() {
    return maxUses;
  }

  // Số lượt đã dùng
  public int uses() {
    return uses;
  }

  // Thời điểm hết hạn
  public Instant expiresAt() {
    return expiresAt;
  }

  // Thời điểm thu hồi, null nếu chưa thu hồi
  public Instant revokedAt() {
    return revokedAt;
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }
}

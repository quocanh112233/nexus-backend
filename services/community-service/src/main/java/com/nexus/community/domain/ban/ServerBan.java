package com.nexus.community.domain.ban;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Aggregate lệnh cấm user khỏi server, vừa chặn join vừa là audit tối thiểu
public final class ServerBan {

  private static final int MAX_REASON = 512;

  private final ServerId serverId;
  private final UserId userId;
  private final UserId bannedBy;
  private final String reason;
  private final Instant createdAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private ServerBan(
      ServerId serverId, UserId userId, UserId bannedBy, String reason, Instant createdAt) {
    this.serverId = serverId;
    this.userId = userId;
    this.bannedBy = bannedBy;
    this.reason = reason;
    this.createdAt = createdAt;
  }

  // Tạo ban mới, trim lý do, rỗng thành null, quá 512 ký tự bị từ chối, phát MemberBanned
  public static ServerBan create(
      ServerId serverId,
      UserId target,
      UserId bannedBy,
      String reasonOrNull,
      boolean wasMember,
      Instant now) {
    String reason = reasonOrNull == null ? null : reasonOrNull.trim();
    if (reason != null && reason.isEmpty()) {
      reason = null;
    }
    if (reason != null && reason.length() > MAX_REASON) {
      throw new DomainValidationException("reason must be at most " + MAX_REASON + " characters");
    }
    ServerBan ban = new ServerBan(serverId, target, bannedBy, reason, now);
    ban.events.add(new MemberBanned(serverId, target, bannedBy, reason, wasMember));
    return ban;
  }

  // Dựng lại ban từ lưu trữ, không phát sự kiện
  public static ServerBan rehydrate(
      ServerId serverId, UserId userId, UserId bannedBy, String reason, Instant createdAt) {
    return new ServerBan(serverId, userId, bannedBy, reason, createdAt);
  }

  // Gỡ ban và phát MemberUnbanned
  public void lift(UserId by) {
    events.add(new MemberUnbanned(serverId, userId, by));
  }

  // Trả các sự kiện đã phát rồi xóa hàng đợi
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Id server
  public ServerId serverId() {
    return serverId;
  }

  // Id người bị ban
  public UserId userId() {
    return userId;
  }

  // Id người ra lệnh ban
  public UserId bannedBy() {
    return bannedBy;
  }

  // Lý do ban, null khi không có
  public String reason() {
    return reason;
  }

  // Thời điểm ban
  public Instant createdAt() {
    return createdAt;
  }
}

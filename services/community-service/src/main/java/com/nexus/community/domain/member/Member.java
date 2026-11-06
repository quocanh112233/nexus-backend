package com.nexus.community.domain.member;

import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Aggregate thành viên của server và phát sự kiện miền
public final class Member {

  private final ServerId serverId;
  private final UserId userId;
  private final Instant joinedAt;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua join hoặc rehydrate
  private Member(ServerId serverId, UserId userId, Instant joinedAt) {
    this.serverId = serverId;
    this.userId = userId;
    this.joinedAt = joinedAt;
  }

  // Cho user vào server và phát MemberJoined
  public static Member join(ServerId serverId, UserId userId, Instant now) {
    Member member = new Member(serverId, userId, now);
    member.events.add(new MemberJoined(serverId, userId, now));
    return member;
  }

  // Dựng lại thành viên từ lưu trữ, không phát sự kiện
  public static Member rehydrate(ServerId serverId, UserId userId, Instant joinedAt) {
    return new Member(serverId, userId, joinedAt);
  }

  // Cho thành viên rời server và phát MemberLeft
  public void leave() {
    events.add(new MemberLeft(serverId, userId));
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

  // Id người dùng
  public UserId userId() {
    return userId;
  }

  // Thời điểm vào server
  public Instant joinedAt() {
    return joinedAt;
  }

  // Danh sách role của thành viên, rỗng ở P2
  public List<RoleId> roleIds() {
    return List.of();
  }
}

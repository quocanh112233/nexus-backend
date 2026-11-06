package com.nexus.community.domain.member;

import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Aggregate thành viên của server và phát sự kiện miền
public final class Member {

  private final ServerId serverId;
  private final UserId userId;
  private final Instant joinedAt;
  private List<RoleId> roleIds;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua join hoặc rehydrate
  private Member(ServerId serverId, UserId userId, Instant joinedAt, List<RoleId> roleIds) {
    this.serverId = serverId;
    this.userId = userId;
    this.joinedAt = joinedAt;
    this.roleIds = List.copyOf(roleIds);
  }

  // Cho user vào server và phát MemberJoined
  public static Member join(ServerId serverId, UserId userId, Instant now) {
    Member member = new Member(serverId, userId, now, List.of());
    member.events.add(new MemberJoined(serverId, userId, now));
    return member;
  }

  // Dựng lại thành viên từ lưu trữ, không phát sự kiện
  public static Member rehydrate(
      ServerId serverId, UserId userId, Instant joinedAt, List<RoleId> roleIds) {
    return new Member(serverId, userId, joinedAt, roleIds);
  }

  // Cho thành viên rời server và phát MemberLeft
  public void leave() {
    events.add(new MemberLeft(serverId, userId));
  }

  // Kick thành viên khỏi server và phát MemberKicked
  public void kick(UserId by) {
    events.add(new MemberKicked(serverId, userId, by));
  }

  // Thay tập role, khác tập hiện tại thì phát MemberRolesChanged với danh sách mới
  public void replaceRoles(Collection<RoleId> newRoleIds) {
    Set<RoleId> distinct = new LinkedHashSet<>(newRoleIds);
    if (distinct.equals(Set.copyOf(roleIds))) {
      return;
    }
    roleIds = List.copyOf(distinct);
    events.add(new MemberRolesChanged(serverId, userId, roleIds));
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

  // Danh sách role của thành viên, không gồm @everyone, chỉ đọc
  public List<RoleId> roleIds() {
    return roleIds;
  }
}

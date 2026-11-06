package com.nexus.community.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm thử sự kiện của aggregate Member
class MemberTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Kịch bản: vào server phát MemberJoined mang thời điểm vào
  // Input: Member.join(serverId, userId, NOW)
  // Output: 1 MemberJoined với joinedAt = NOW, roleIds rỗng
  @Test
  void joinEmitsMemberJoinedWithTime() {
    ServerId serverId = ServerId.newId();
    UserId userId = UserId.newId();

    Member member = Member.join(serverId, userId, NOW);

    assertThat(member.pullEvents())
        .singleElement()
        .isEqualTo(new MemberJoined(serverId, userId, NOW));
    assertThat(member.roleIds()).isEmpty();
  }

  // Kịch bản: rời server phát MemberLeft
  // Input: rehydrate với roleIds rỗng rồi leave()
  // Output: 1 MemberLeft với serverId và userId
  @Test
  void leaveEmitsMemberLeft() {
    ServerId serverId = ServerId.newId();
    UserId userId = UserId.newId();
    Member member = Member.rehydrate(serverId, userId, NOW, List.of());

    member.leave();

    assertThat(member.pullEvents()).singleElement().isEqualTo(new MemberLeft(serverId, userId));
  }

  // Kịch bản: rehydrate giữ role id và danh sách trả về không sửa được
  // Input: rehydrate với 2 roleId rồi gọi roleIds().add
  // Output: roleIds() đúng thứ tự 2 id, add ném UnsupportedOperationException
  @Test
  void rehydrateKeepsRoleIdsUnmodifiable() {
    RoleId a = RoleId.newId();
    RoleId b = RoleId.newId();
    Member member = Member.rehydrate(ServerId.newId(), UserId.newId(), NOW, List.of(a, b));

    assertThat(member.roleIds()).containsExactly(a, b);
    assertThatThrownBy(() -> member.roleIds().add(RoleId.newId()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // Kịch bản: đổi tập role phát đúng một MemberRolesChanged
  // Input: thành viên không role, replaceRoles([a, b, a])
  // Output: roleIds là [a, b] và 1 MemberRolesChanged mang danh sách mới
  @Test
  void replaceRolesWithDifferentSetEmitsEvent() {
    ServerId serverId = ServerId.newId();
    UserId userId = UserId.newId();
    RoleId a = RoleId.newId();
    RoleId b = RoleId.newId();
    Member member = Member.rehydrate(serverId, userId, NOW, List.of());

    member.replaceRoles(List.of(a, b, a));

    assertThat(member.roleIds()).containsExactly(a, b);
    assertThat(member.pullEvents())
        .containsExactly(new MemberRolesChanged(serverId, userId, List.of(a, b)));
  }

  // Kịch bản: cùng tập role khác thứ tự thì không làm gì
  // Input: thành viên có [a, b], replaceRoles([b, a])
  // Output: không phát sự kiện, roleIds giữ nguyên [a, b]
  @Test
  void replaceRolesWithSameSetEmitsNothing() {
    RoleId a = RoleId.newId();
    RoleId b = RoleId.newId();
    Member member = Member.rehydrate(ServerId.newId(), UserId.newId(), NOW, List.of(a, b));

    member.replaceRoles(List.of(b, a));

    assertThat(member.pullEvents()).isEmpty();
    assertThat(member.roleIds()).containsExactly(a, b);
  }
}

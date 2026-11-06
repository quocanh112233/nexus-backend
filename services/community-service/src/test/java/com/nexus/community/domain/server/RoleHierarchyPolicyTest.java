package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm thử luật thứ bậc role và Actor
class RoleHierarchyPolicyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final PermissionSet NONE = new PermissionSet(0);

  // Dựng actor không phải chủ với quyền và vị trí cao nhất cho trước
  private static Actor actor(int highest, Permission... permissions) {
    return new Actor(UserId.newId(), false, NONE.with(permissions), highest);
  }

  // Dựng actor là chủ server
  private static Actor owner() {
    return new Actor(UserId.newId(), true, PermissionSet.ALL, 0);
  }

  // Dựng role thường tại vị trí cho trước
  private static Role role(int position, Permission... permissions) {
    return new Role(RoleId.newId(), "r" + position, 0, NONE.with(permissions), position, false);
  }

  // Kịch bản: chủ quản lý được mọi role
  // Input: canManageRole(owner, role position 99)
  // Output: true
  @Test
  void manageRoleOwner() {
    assertThat(RoleHierarchyPolicy.canManageRole(owner(), role(99))).isTrue();
  }

  // Kịch bản: role thấp hơn actor thì quản lý được
  // Input: actor highest 5, role position 4
  // Output: true
  @Test
  void manageRoleLower() {
    assertThat(RoleHierarchyPolicy.canManageRole(actor(5), role(4))).isTrue();
  }

  // Kịch bản: role ngang cấp actor thì không quản lý được
  // Input: actor highest 5, role position 5
  // Output: false
  @Test
  void manageRoleEqual() {
    assertThat(RoleHierarchyPolicy.canManageRole(actor(5), role(5))).isFalse();
  }

  // Kịch bản: role cao hơn actor thì không quản lý được
  // Input: actor highest 5, role position 6
  // Output: false
  @Test
  void manageRoleHigher() {
    assertThat(RoleHierarchyPolicy.canManageRole(actor(5), role(6))).isFalse();
  }

  // Kịch bản: chủ đặt role ở vị trí nào cũng được
  // Input: canPlaceAt(owner, 99, true) và canPlaceAt(owner, 99, false)
  // Output: cả hai true
  @Test
  void placeOwner() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(owner(), 99, true)).isTrue();
    assertThat(RoleHierarchyPolicy.canPlaceAt(owner(), 99, false)).isTrue();
  }

  // Kịch bản: chèn role mới ngang cấp actor được phép
  // Input: actor highest 5, position 5, insertingNew = true
  // Output: true
  @Test
  void placeNewEqual() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 5, true)).isTrue();
  }

  // Kịch bản: chèn role mới cao hơn actor bị từ chối
  // Input: actor highest 5, position 6, insertingNew = true
  // Output: false
  @Test
  void placeNewHigher() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 6, true)).isFalse();
  }

  // Kịch bản: chèn role mới thấp hơn actor được phép
  // Input: actor highest 5, position 3, insertingNew = true
  // Output: true
  @Test
  void placeNewLower() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 3, true)).isTrue();
  }

  // Kịch bản: dời role có sẵn lên ngang cấp actor bị từ chối
  // Input: actor highest 5, position 5, insertingNew = false
  // Output: false
  @Test
  void placeExistingEqual() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 5, false)).isFalse();
  }

  // Kịch bản: dời role có sẵn xuống thấp hơn actor được phép
  // Input: actor highest 5, position 4, insertingNew = false
  // Output: true
  @Test
  void placeExistingLower() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 4, false)).isTrue();
  }

  // Kịch bản: dời role có sẵn lên cao hơn actor bị từ chối
  // Input: actor highest 5, position 6, insertingNew = false
  // Output: false
  @Test
  void placeExistingHigher() {
    assertThat(RoleHierarchyPolicy.canPlaceAt(actor(5), 6, false)).isFalse();
  }

  // Kịch bản: chủ cấp quyền nào cũng được
  // Input: canGrant(owner, ALL)
  // Output: true
  @Test
  void grantOwner() {
    assertThat(RoleHierarchyPolicy.canGrant(owner(), PermissionSet.ALL)).isTrue();
  }

  // Kịch bản: actor cấp được tập quyền nằm trong quyền của mình
  // Input: actor có KICK_MEMBERS và BAN_MEMBERS, đổi bit KICK_MEMBERS
  // Output: true
  @Test
  void grantSubset() {
    Actor a = actor(5, Permission.KICK_MEMBERS, Permission.BAN_MEMBERS);
    assertThat(RoleHierarchyPolicy.canGrant(a, NONE.with(Permission.KICK_MEMBERS))).isTrue();
  }

  // Kịch bản: actor không cấp được quyền mình không có
  // Input: actor có KICK_MEMBERS, đổi bit KICK_MEMBERS và BAN_MEMBERS
  // Output: false
  @Test
  void grantSuperset() {
    Actor a = actor(5, Permission.KICK_MEMBERS);
    PermissionSet changed = NONE.with(Permission.KICK_MEMBERS, Permission.BAN_MEMBERS);
    assertThat(RoleHierarchyPolicy.canGrant(a, changed)).isFalse();
  }

  // Kịch bản: không đổi bit nào thì luôn hợp lệ
  // Input: actor không quyền, changedBits rỗng
  // Output: true
  @Test
  void grantNothing() {
    assertThat(RoleHierarchyPolicy.canGrant(actor(1), NONE)).isTrue();
  }

  // Kịch bản: không ai moderate được chủ server kể cả chủ khác
  // Input: actor owner, targetIsOwner = true
  // Output: false
  @Test
  void moderateOwnerTarget() {
    assertThat(RoleHierarchyPolicy.canModerate(owner(), UserId.newId(), true, 0)).isFalse();
  }

  // Kịch bản: không tự moderate chính mình
  // Input: target trùng userId của actor
  // Output: false
  @Test
  void moderateSelf() {
    Actor a = actor(9);
    assertThat(RoleHierarchyPolicy.canModerate(a, a.userId(), false, 0)).isFalse();
  }

  // Kịch bản: chủ moderate được mọi member khác
  // Input: actor owner, target thường highest 99
  // Output: true
  @Test
  void moderateByOwner() {
    assertThat(RoleHierarchyPolicy.canModerate(owner(), UserId.newId(), false, 99)).isTrue();
  }

  // Kịch bản: target không phải member thì cho phép (ban trước)
  // Input: targetHighestOrNull = null
  // Output: true
  @Test
  void moderateNonMember() {
    assertThat(RoleHierarchyPolicy.canModerate(actor(1), UserId.newId(), false, null)).isTrue();
  }

  // Kịch bản: target thấp hơn actor thì moderate được
  // Input: actor highest 5, target highest 4
  // Output: true
  @Test
  void moderateLower() {
    assertThat(RoleHierarchyPolicy.canModerate(actor(5), UserId.newId(), false, 4)).isTrue();
  }

  // Kịch bản: target ngang cấp actor thì bị từ chối
  // Input: actor highest 5, target highest 5
  // Output: false
  @Test
  void moderateEqual() {
    assertThat(RoleHierarchyPolicy.canModerate(actor(5), UserId.newId(), false, 5)).isFalse();
  }

  // Kịch bản: Mod (position 2) ban Admin (position 5) bị từ chối (ví dụ 4)
  // Input: actor highest 2, target highest 5
  // Output: false
  @Test
  void moderateHigher() {
    assertThat(RoleHierarchyPolicy.canModerate(actor(2), UserId.newId(), false, 5)).isFalse();
  }

  // Kịch bản: Actor.of cho chủ server cho ALL
  // Input: Actor.of(server, member là owner)
  // Output: owner = true, serverPermissions = ALL
  @Test
  void actorOfOwner() {
    UserId ownerId = UserId.newId();
    Server server = Server.create(new ServerName("Nexus"), ownerId, NOW);
    Actor a = Actor.of(server, Member.join(server.id(), ownerId, NOW));
    assertThat(a.owner()).isTrue();
    assertThat(a.serverPermissions()).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: member không role có vị trí cao nhất 0 của @everyone
  // Input: Actor.of(server, member không role)
  // Output: owner = false, highestPosition = 0
  @Test
  void actorOfNoRolesIsEveryonePosition() {
    Server server = Server.create(new ServerName("Nexus"), UserId.newId(), NOW);
    Actor a = Actor.of(server, Member.join(server.id(), UserId.newId(), NOW));
    assertThat(a.owner()).isFalse();
    assertThat(a.highestPosition()).isZero();
  }

  // Kịch bản: vị trí cao nhất là max các role của member
  // Input: member có role position 2 và 7, server còn role position 9
  // Output: highestPosition = 7
  @Test
  void actorOfHighestIsMax() {
    Role low = role(2);
    Role high = role(7);
    Role other = role(9);
    Role everyone =
        new Role(RoleId.newId(), "@everyone", 0, PermissionSet.EVERYONE_DEFAULT, 0, true);
    Server server =
        Server.rehydrate(
            ServerId.newId(),
            new ServerName("Nexus"),
            UserId.newId(),
            List.of(everyone, low, high, other),
            NOW,
            NOW,
            0);
    Member m = Member.rehydrate(server.id(), UserId.newId(), NOW, List.of(low.id(), high.id()));
    assertThat(Actor.of(server, m).highestPosition()).isEqualTo(7);
  }
}

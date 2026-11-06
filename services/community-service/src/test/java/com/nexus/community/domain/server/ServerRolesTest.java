package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm thử quản lý role trong aggregate Server
class ServerRolesTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant LATER = NOW.plusSeconds(5);
  private static final PermissionSet P = new PermissionSet(0).with(Permission.SEND_MESSAGES);

  // Dựng server mới đã xả sự kiện khởi tạo
  private static Server newServer() {
    Server server = Server.create(new ServerName("Nexus"), UserId.newId(), NOW);
    server.pullEvents();
    return server;
  }

  // Trả tên các role theo thứ tự vị trí tăng dần
  private static List<String> names(Server server) {
    return server.roles().stream().map(Role::name).toList();
  }

  // Trả vị trí các role theo thứ tự trong server
  private static List<Integer> positions(Server server) {
    return server.roles().stream().map(Role::position).toList();
  }

  // Kịch bản: tạo role không nêu vị trí thì vào vị trí 1 và đẩy role cũ lên
  // Input: createRole "a" rồi "b" với position null
  // Output: b ở vị trí 1, a ở vị trí 2, @everyone vị trí 0, mỗi lần một RoleCreated
  @Test
  void createWithoutPositionGoesToBottomAndPushesOthersUp() {
    Server server = newServer();

    Role a = server.createRole("a", 1, P, null, NOW);
    Role b = server.createRole("b", 2, P, null, LATER);

    assertThat(names(server)).containsExactly("@everyone", "b", "a");
    assertThat(positions(server)).containsExactly(0, 1, 2);
    assertThat(server.pullEvents())
        .containsExactly(
            new RoleCreated(server.id(), a.id(), "a", P, 1),
            new RoleCreated(server.id(), b.id(), "b", P, 1));
    assertThat(server.updatedAt()).isEqualTo(LATER);
  }

  // Kịch bản: chèn role vào giữa thì đánh số lại dày
  // Input: có a, b, c rồi createRole "mid" ở vị trí 2
  // Output: thứ tự @everyone, a, mid, b, c ở vị trí 0..4 dày, RoleCreated mang vị trí 2
  @Test
  void createInMiddleRenumbersDensely() {
    Server server = newServer();
    server.createRole("c", 0, P, null, NOW);
    server.createRole("b", 0, P, null, NOW);
    server.createRole("a", 0, P, null, NOW);
    server.pullEvents();

    Role mid = server.createRole("mid", 0, P, 2, LATER);

    assertThat(names(server)).containsExactly("@everyone", "a", "mid", "b", "c");
    assertThat(positions(server)).containsExactly(0, 1, 2, 3, 4);
    assertThat(server.pullEvents())
        .containsExactly(new RoleCreated(server.id(), mid.id(), "mid", P, 2));
  }

  // Kịch bản: tạo role với dữ liệu sai bị từ chối
  // Input: tên rỗng, tên 101 ký tự, màu âm, màu quá lớn, vị trí 0 và vị trí n+2
  // Output: DomainValidationException cho cả sáu trường hợp, không phát sự kiện
  @Test
  void createRejectsInvalidInput() {
    Server server = newServer();

    assertThatThrownBy(() -> server.createRole("  ", 0, P, null, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.createRole("x".repeat(101), 0, P, null, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.createRole("a", -1, P, null, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.createRole("a", 0x1000000, P, null, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.createRole("a", 0, P, 0, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.createRole("a", 0, P, 2, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThat(server.pullEvents()).isEmpty();
  }

  // Kịch bản: updateRole đổi vị trí vẫn giữ thứ tự dày
  // Input: có a(1), b(2), c(3) rồi đưa c về vị trí 1
  // Output: thứ tự c, a, b ở vị trí 1, 2, 3 và đúng một RoleUpdated mang vị trí 1
  @Test
  void updateRoleMovesPositionKeepingDenseOrder() {
    Server server = newServer();
    Role c = server.createRole("c", 0, P, null, NOW);
    server.createRole("b", 0, P, null, NOW);
    server.createRole("a", 0, P, null, NOW);
    server.pullEvents();

    server.updateRole(c.id(), new RoleChanges(null, null, null, 1), LATER);

    assertThat(names(server)).containsExactly("@everyone", "c", "a", "b");
    assertThat(positions(server)).containsExactly(0, 1, 2, 3);
    assertThat(server.pullEvents())
        .containsExactly(new RoleUpdated(server.id(), c.id(), "c", P, 1));
    assertThat(server.updatedAt()).isEqualTo(LATER);
  }

  // Kịch bản: updateRole đổi tên, màu và quyền
  // Input: đổi role "a" thành " renamed " màu 5 quyền rỗng
  // Output: tên được trim, màu 5, quyền rỗng, một RoleUpdated, position giữ nguyên
  @Test
  void updateRoleChangesFields() {
    Server server = newServer();
    Role a = server.createRole("a", 1, P, null, NOW);
    server.pullEvents();
    PermissionSet none = new PermissionSet(0);

    server.updateRole(a.id(), new RoleChanges(" renamed ", 5, none, null), LATER);

    Role updated = server.findRole(a.id()).orElseThrow();
    assertThat(updated.name()).isEqualTo("renamed");
    assertThat(updated.color()).isEqualTo(5);
    assertThat(updated.permissions()).isEqualTo(none);
    assertThat(updated.position()).isEqualTo(1);
    assertThat(server.pullEvents())
        .containsExactly(new RoleUpdated(server.id(), a.id(), "renamed", none, 1));
  }

  // Kịch bản: updateRole không đổi gì thì không phát sự kiện và không đổi updatedAt
  // Input: RoleChanges toàn null, rồi cùng giá trị hiện tại
  // Output: pullEvents rỗng, updatedAt vẫn NOW
  @Test
  void updateRoleNoopEmitsNothing() {
    Server server = newServer();
    Role a = server.createRole("a", 1, P, null, NOW);
    server.pullEvents();

    server.updateRole(a.id(), new RoleChanges(null, null, null, null), LATER);
    server.updateRole(a.id(), new RoleChanges("a", 1, P, 1), LATER);

    assertThat(server.pullEvents()).isEmpty();
    assertThat(server.updatedAt()).isEqualTo(NOW);
  }

  // Kịch bản: @everyone không đổi được tên hay vị trí nhưng đổi được quyền
  // Input: updateRole @everyone với name, rồi position, rồi chỉ permissions
  // Output: hai lần đầu ném DomainValidationException, lần ba thành công với một RoleUpdated
  @Test
  void everyoneCannotBeRenamedOrMoved() {
    Server server = newServer();
    server.createRole("a", 0, P, null, NOW);
    server.pullEvents();
    RoleId everyone = server.defaultRole().id();

    assertThatThrownBy(
            () -> server.updateRole(everyone, new RoleChanges("x", null, null, null), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () -> server.updateRole(everyone, new RoleChanges(null, null, null, 1), LATER))
        .isInstanceOf(DomainValidationException.class);
    server.updateRole(everyone, new RoleChanges(null, null, P, null), LATER);

    assertThat(server.defaultRole().permissions()).isEqualTo(P);
    assertThat(server.defaultRole().position()).isZero();
    assertThat(server.pullEvents())
        .containsExactly(new RoleUpdated(server.id(), everyone, "@everyone", P, 0));
  }

  // Kịch bản: updateRole với dữ liệu sai hoặc role lạ bị từ chối
  // Input: vị trí 0, vị trí n+1, tên rỗng, màu âm, role không tồn tại
  // Output: DomainValidationException cho mọi trường hợp, không phát sự kiện
  @Test
  void updateRoleRejectsInvalid() {
    Server server = newServer();
    Role a = server.createRole("a", 0, P, null, NOW);
    server.pullEvents();

    assertThatThrownBy(() -> server.updateRole(a.id(), new RoleChanges(null, null, null, 0), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> server.updateRole(a.id(), new RoleChanges(null, null, null, 2), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () -> server.updateRole(a.id(), new RoleChanges("", null, null, null), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () -> server.updateRole(a.id(), new RoleChanges(null, -1, null, null), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () -> server.updateRole(RoleId.newId(), new RoleChanges("x", null, null, null), LATER))
        .isInstanceOf(DomainValidationException.class);
    assertThat(server.pullEvents()).isEmpty();
  }

  // Kịch bản: removeRole đánh số lại dày và phát RoleDeleted
  // Input: có a(1), b(2), c(3) rồi gỡ b
  // Output: còn a(1), c(2), một RoleDeleted, updatedAt = LATER
  @Test
  void removeRoleRenumbersAndEmitsDeleted() {
    Server server = newServer();
    server.createRole("c", 0, P, null, NOW);
    Role b = server.createRole("b", 0, P, null, NOW);
    server.createRole("a", 0, P, null, NOW);
    server.pullEvents();

    server.removeRole(b.id(), LATER);

    assertThat(names(server)).containsExactly("@everyone", "a", "c");
    assertThat(positions(server)).containsExactly(0, 1, 2);
    assertThat(server.pullEvents()).containsExactly(new RoleDeleted(server.id(), b.id()));
    assertThat(server.updatedAt()).isEqualTo(LATER);
  }

  // Kịch bản: findRole và defaultRole truy vấn role
  // Input: findRole id có thật, id lạ, và defaultRole
  // Output: Optional có role, Optional rỗng, defaultRole là @everyone
  @Test
  void findRoleAndDefaultRole() {
    Server server = newServer();
    Role a = server.createRole("a", 0, P, null, NOW);

    assertThat(server.findRole(a.id())).contains(a);
    assertThat(server.findRole(RoleId.newId())).isEmpty();
    assertThat(server.defaultRole().name()).isEqualTo("@everyone");
  }
}

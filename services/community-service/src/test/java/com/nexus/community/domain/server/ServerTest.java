package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm thử hành vi và sự kiện của aggregate Server
class ServerTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Dựng một Server hợp lệ với dữ liệu mẫu cố định
  private static Server newServer() {
    return Server.create(new ServerName("Nexus"), UserId.newId(), NOW);
  }

  // Kịch bản: tạo server có đúng một role @everyone mặc định và một sự kiện
  // Input: Server.create rồi pullEvents
  // Output: 1 role mặc định position 0, quyền EVERYONE_DEFAULT, 1 ServerCreated
  @Test
  void createsEveryoneRoleAndOneEvent() {
    Server server = newServer();

    assertThat(server.roles())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.name()).isEqualTo("@everyone");
              assertThat(r.isDefault()).isTrue();
              assertThat(r.position()).isZero();
              assertThat(r.color()).isZero();
              assertThat(r.permissions()).isEqualTo(PermissionSet.EVERYONE_DEFAULT);
            });
    assertThat(server.pullEvents())
        .singleElement()
        .isEqualTo(new ServerCreated(server.id(), server.name(), server.ownerId()));
  }

  // Kịch bản: không gỡ được role mặc định
  // Input: removeRole với id của role @everyone
  // Output: ném DomainValidationException, role vẫn còn
  @Test
  void everyoneRoleCannotBeRemoved() {
    Server server = newServer();
    RoleId everyone = server.roles().get(0).id();

    assertThatThrownBy(() -> server.removeRole(everyone))
        .isInstanceOf(DomainValidationException.class);
    assertThat(server.roles()).hasSize(1);
  }

  // Kịch bản: đổi tên phát ServerUpdated và cập nhật updatedAt
  // Input: rename(ServerName "New", NOW+5s)
  // Output: 1 ServerUpdated mang tên mới, updatedAt = NOW+5s
  @Test
  void renameEmitsServerUpdated() {
    Server server = newServer();
    server.pullEvents();

    server.rename(new ServerName("New"), NOW.plusSeconds(5));

    assertThat(server.pullEvents())
        .singleElement()
        .isEqualTo(new ServerUpdated(server.id(), new ServerName("New")));
    assertThat(server.updatedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  // Kịch bản: pullEvents trả bản sao rồi xóa hàng đợi
  // Input: create rồi gọi pullEvents hai lần
  // Output: lần 1 có 1 sự kiện, lần 2 rỗng
  @Test
  void pullEventsClearsQueue() {
    Server server = newServer();

    assertThat(server.pullEvents()).hasSize(1);
    assertThat(server.pullEvents()).isEmpty();
  }

  // Kịch bản: rehydrate không phát sự kiện và giữ nguyên trường lưu trữ
  // Input: Server.rehydrate với version 7
  // Output: pullEvents rỗng, version 7, isOwner đúng cho chủ
  @Test
  void rehydrateEmitsNothing() {
    UserId owner = UserId.newId();
    Role role = new Role(RoleId.newId(), "@everyone", 0, PermissionSet.EVERYONE_DEFAULT, 0, true);

    Server server =
        Server.rehydrate(ServerId.newId(), new ServerName("S"), owner, List.of(role), NOW, NOW, 7);

    assertThat(server.pullEvents()).isEmpty();
    assertThat(server.version()).isEqualTo(7);
    assertThat(server.isOwner(owner)).isTrue();
  }
}

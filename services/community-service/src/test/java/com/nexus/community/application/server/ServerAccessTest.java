package com.nexus.community.application.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Kiểm tra ServerAccess phân biệt không tìm thấy, thiếu quyền và hợp lệ
class ServerAccessTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final ServerAccess access = new ServerAccess(servers, members);
  private final UserId owner = UserId.newId();
  private final Server server = Server.create(new ServerName("S"), owner, Instant.now());

  // Kịch bản: server không tồn tại hoặc người gọi không phải thành viên đều là NotFound
  // Input: requireMember với server không có, rồi với server có nhưng user chưa tham gia
  // Output: cả hai ném ServerNotFoundException
  @Test
  void nonMemberGetsNotFound() {
    when(servers.findById(server.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.requireMember(server.id(), owner))
        .isInstanceOf(ServerNotFoundException.class);

    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.requireMember(server.id(), owner))
        .isInstanceOf(ServerNotFoundException.class);
  }

  // Kịch bản: thành viên không phải chủ bị từ chối khi cần quyền chủ
  // Input: requireOwner với user là thành viên nhưng không phải owner
  // Output: ném PermissionDeniedException
  @Test
  void memberNonOwnerGetsPermissionDenied() {
    UserId member = UserId.newId();
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), member))
        .thenReturn(Optional.of(Member.join(server.id(), member, Instant.now())));

    assertThatThrownBy(() -> access.requireOwner(server.id(), member))
        .isInstanceOf(PermissionDeniedException.class);
  }

  // Kịch bản: chủ server là thành viên vượt qua cả hai kiểm tra
  // Input: requireMember và requireOwner với owner đã là thành viên
  // Output: trả về đúng server
  @Test
  void ownerPasses() {
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner))
        .thenReturn(Optional.of(Member.join(server.id(), owner, Instant.now())));

    assertThat(access.requireMember(server.id(), owner)).isSameAs(server);
    assertThat(access.requireOwner(server.id(), owner)).isSameAs(server);
  }

  // Kịch bản: requirePermission với người không phải thành viên hoặc server không có là NotFound
  // Input: server không có, rồi server có nhưng user chưa tham gia
  // Output: cả hai ném ServerNotFoundException
  @Test
  void requirePermissionNonMemberGetsNotFound() {
    when(servers.findById(server.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.requirePermission(server.id(), owner, Permission.MANAGE_ROLES))
        .isInstanceOf(ServerNotFoundException.class);

    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> access.requirePermission(server.id(), owner, Permission.MANAGE_ROLES))
        .isInstanceOf(ServerNotFoundException.class);
  }

  // Kịch bản: thành viên chỉ có quyền của @everyone bị từ chối khi cần MANAGE_ROLES
  // Input: requirePermission MANAGE_ROLES với thành viên không có role nào
  // Output: ném PermissionDeniedException
  @Test
  void requirePermissionWithoutBitGetsDenied() {
    UserId user = UserId.newId();
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), user))
        .thenReturn(Optional.of(Member.join(server.id(), user, Instant.now())));

    assertThatThrownBy(() -> access.requirePermission(server.id(), user, Permission.MANAGE_ROLES))
        .isInstanceOf(PermissionDeniedException.class);
  }

  // Kịch bản: thành viên có role cấp quyền thì qua, trả server, member và actor
  // Input: requirePermission MANAGE_ROLES với thành viên giữ role có MANAGE_ROLES
  // Output: Authorized chứa đúng server, member, actor không phải owner mang quyền của role
  @Test
  void requirePermissionWithRoleBitPasses() {
    UserId user = UserId.newId();
    Role role =
        server.createRole(
            "Lead", 0, new PermissionSet(0).with(Permission.MANAGE_ROLES), null, Instant.now());
    Member member = Member.rehydrate(server.id(), user, Instant.now(), List.of(role.id()));
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), user)).thenReturn(Optional.of(member));

    Authorized auth = access.requirePermission(server.id(), user, Permission.MANAGE_ROLES);

    assertThat(auth.server()).isSameAs(server);
    assertThat(auth.member()).isSameAs(member);
    assertThat(auth.actor().owner()).isFalse();
    assertThat(auth.actor().serverPermissions().has(Permission.MANAGE_ROLES)).isTrue();
    assertThat(auth.actor().highestPosition()).isEqualTo(role.position());
  }

  // Kịch bản: ADMINISTRATOR qua mọi quyền dù không có bit đó
  // Input: requirePermission BAN_MEMBERS với thành viên giữ role chỉ có ADMINISTRATOR
  // Output: không ném lỗi, actor có đủ quyền
  @Test
  void administratorPassesAnyPermission() {
    UserId user = UserId.newId();
    Role admin =
        server.createRole(
            "Admin", 0, new PermissionSet(0).with(Permission.ADMINISTRATOR), null, Instant.now());
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), user))
        .thenReturn(
            Optional.of(Member.rehydrate(server.id(), user, Instant.now(), List.of(admin.id()))));

    Authorized auth = access.requirePermission(server.id(), user, Permission.BAN_MEMBERS);

    assertThat(auth.actor().serverPermissions()).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: chủ server qua mọi quyền kể cả khi không có role nào
  // Input: requirePermission MANAGE_ROLES với owner đã là thành viên
  // Output: Authorized có actor là owner
  @Test
  void ownerPassesRequirePermission() {
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner))
        .thenReturn(Optional.of(Member.join(server.id(), owner, Instant.now())));

    Authorized auth = access.requirePermission(server.id(), owner, Permission.MANAGE_ROLES);

    assertThat(auth.actor().owner()).isTrue();
  }
}

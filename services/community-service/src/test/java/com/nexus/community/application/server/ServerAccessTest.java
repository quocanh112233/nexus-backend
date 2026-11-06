package com.nexus.community.application.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
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
}

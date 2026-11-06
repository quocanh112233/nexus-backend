package com.nexus.community.application.member;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// Kiểm tra LeaveServerUseCase khóa cặp trước khi đọc thành viên và vẫn chặn chủ server rời
class LeaveServerUseCaseTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final MemberLock lock = mock(MemberLock.class);
  private final LeaveServerUseCase useCase =
      new LeaveServerUseCase(
          new ServerAccess(servers, members),
          members,
          mock(ChannelOverwriteRepository.class),
          lock,
          event -> {});
  private final UserId owner = UserId.newId();
  private final Server server = Server.create(new ServerName("S"), owner, Instant.now());

  // Kịch bản: thành viên rời server thì khóa cặp (server,user) được lấy trước khi đọc thành viên
  // Input: member thường gọi execute, repository trả server và thành viên
  // Output: lock gọi trước find của members và remove được gọi một lần
  @Test
  void locksPairBeforeReadingMember() {
    UserId user = UserId.newId();
    Member member = Member.join(server.id(), user, Instant.now());
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), user)).thenReturn(Optional.of(member));

    useCase.execute(server.id(), user);

    InOrder order = inOrder(lock, members);
    order.verify(lock).lock(server.id(), user);
    order.verify(members, atLeastOnce()).find(server.id(), user);
    verify(members).remove(server.id(), user);
  }

  // Kịch bản: chủ server vẫn không được rời dù đã khóa cặp
  // Input: owner gọi execute
  // Output: PermissionDeniedException và không remove thành viên
  @Test
  void ownerStillCannotLeave() {
    Member member = Member.join(server.id(), owner, Instant.now());
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner)).thenReturn(Optional.of(member));

    assertThatThrownBy(() -> useCase.execute(server.id(), owner))
        .isInstanceOf(PermissionDeniedException.class);

    verify(members, never()).remove(server.id(), owner);
  }
}

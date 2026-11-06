package com.nexus.community.application.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.server.ServerUpdated;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// Kiểm tra RenameServerUseCase đổi tên, lưu và phát ServerUpdated, cần MANAGE_SERVER
class RenameServerUseCaseTest {

  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final List<DomainEvent> published = new ArrayList<>();
  private final DomainEventPublisher publisher = published::add;
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-11-05T01:02:03.123456789Z"), ZoneOffset.UTC);
  private final RenameServerUseCase useCase =
      new RenameServerUseCase(new ServerAccess(servers, members), servers, publisher, clock);
  private final UserId owner = UserId.newId();
  private final Server server = Server.create(new ServerName("Old"), owner, Instant.now());

  // Kịch bản: chủ server đổi tên thành công, lưu và phát đúng một ServerUpdated
  // Input: owner đổi tên server thành "  New  "
  // Output: tóm tắt tên "New", save được gọi, event duy nhất là ServerUpdated
  @Test
  void ownerRenamesSavesAndPublishes() {
    server.pullEvents();
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), owner))
        .thenReturn(Optional.of(Member.join(server.id(), owner, Instant.now())));

    ServerSummary summary = useCase.execute(server.id(), owner, "  New  ");

    assertThat(summary.name()).isEqualTo("New");
    verify(servers).save(server);
    assertThat(published).hasSize(1).first().isInstanceOf(ServerUpdated.class);
  }

  // Kịch bản: thành viên không phải chủ không đổi được tên
  // Input: user thường là thành viên đổi tên server
  // Output: PermissionDeniedException, không lưu và không phát event
  @Test
  void nonOwnerIsRejectedWithoutSideEffects() {
    UserId member = UserId.newId();
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    when(members.find(server.id(), member))
        .thenReturn(Optional.of(Member.join(server.id(), member, Instant.now())));

    assertThatThrownBy(() -> useCase.execute(server.id(), member, "New"))
        .isInstanceOf(PermissionDeniedException.class);

    verify(servers, never()).save(any());
    assertThat(published).isEmpty();
  }
}

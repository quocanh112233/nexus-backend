package com.nexus.community.application.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.ServerSummary;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.member.MemberJoined;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// Kiểm tra AcceptInviteUseCase: khóa, chặn ban, thêm member, tryConsume, phát MemberJoined
class AcceptInviteUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-05T01:02:03.123456Z");

  private final InviteRepository invites = mock(InviteRepository.class);
  private final ServerRepository servers = mock(ServerRepository.class);
  private final MemberRepository members = mock(MemberRepository.class);
  private final List<DomainEvent> published = new ArrayList<>();
  private final ServerBanRepository bans = mock(ServerBanRepository.class);
  private final MemberLock memberLock = mock(MemberLock.class);
  private final DomainEventPublisher publisher = published::add;
  private final AcceptInviteUseCase useCase =
      new AcceptInviteUseCase(
          invites, servers, members, bans, memberLock, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
  private final Server server = Server.create(new ServerName("S"), UserId.newId(), NOW);
  private final InviteCode code = new InviteCode("Abcd1234");
  private final UserId user = UserId.newId();

  // Cho repo trả lời mời 5 lượt còn hạn và server tương ứng
  @BeforeEach
  void stubInviteAndServer() {
    Invite invite =
        Invite.create(code, server.id(), server.ownerId(), 5, NOW.plus(Duration.ofDays(1)), NOW);
    when(invites.findByCode(code)).thenReturn(Optional.of(invite));
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
  }

  // Kịch bản: user đã là thành viên thì trả server mà không tiêu lượt
  // Input: addIfAbsent trả false
  // Output: tóm tắt server, tryConsume không được gọi, không phát event
  @Test
  void alreadyMemberReturnsServerWithoutConsuming() {
    when(members.addIfAbsent(any())).thenReturn(false);

    ServerSummary summary = useCase.execute(code.value(), user);

    assertThat(summary.id()).isEqualTo(server.id().value());
    verify(invites, never()).tryConsume(any(), any());
    assertThat(published).isEmpty();
  }

  // Kịch bản: thêm member được nhưng lời mời hết lượt thì ném để rollback
  // Input: addIfAbsent true, tryConsume false
  // Output: InviteInvalidException, không phát event
  @Test
  void consumeFalseThrowsInvalid() {
    when(members.addIfAbsent(any())).thenReturn(true);
    when(invites.tryConsume(code, NOW)).thenReturn(false);

    assertThatThrownBy(() -> useCase.execute(code.value(), user))
        .isInstanceOf(InviteInvalidException.class);
    assertThat(published).isEmpty();
  }

  // Kịch bản: user mới tham gia thành công
  // Input: addIfAbsent true, tryConsume true
  // Output: tóm tắt server, đúng một MemberJoined của user với joinedAt = NOW
  @Test
  void successAddsMemberAndPublishesMemberJoined() {
    when(members.addIfAbsent(any())).thenReturn(true);
    when(invites.tryConsume(code, NOW)).thenReturn(true);

    ServerSummary summary = useCase.execute(code.value(), user);

    assertThat(summary.id()).isEqualTo(server.id().value());
    assertThat(published).containsExactly(new MemberJoined(server.id(), user, NOW));
  }

  // Kịch bản: user bị ban bị chặn sau khi lấy khóa và trước mọi thay đổi
  // Input: bans.find trả có ban cho user
  // Output: BannedFromServerException; lock gọi trước find; addIfAbsent, tryConsume, event không có
  @Test
  void bannedUserBlockedAfterLockBeforeAnyWrite() {
    when(bans.find(server.id(), user))
        .thenReturn(
            Optional.of(ServerBan.create(server.id(), user, server.ownerId(), null, false, NOW)));

    assertThatThrownBy(() -> useCase.execute(code.value(), user))
        .isInstanceOf(BannedFromServerException.class);

    InOrder order = inOrder(memberLock, bans);
    order.verify(memberLock).lock(server.id(), user);
    order.verify(bans).find(server.id(), user);
    verify(members, never()).addIfAbsent(any());
    verify(invites, never()).tryConsume(any(), any());
    assertThat(published).isEmpty();
  }

  // Kịch bản: mã sai định dạng hoặc không có bị từ chối trước khi đụng tới member
  // Input: mã "bad" và mã "Zzzz9999" không có trong repo
  // Output: cả hai InviteInvalidException, addIfAbsent không được gọi
  @Test
  void unknownOrMalformedCodeThrowsInvalid() {
    assertThatThrownBy(() -> useCase.execute("bad", user))
        .isInstanceOf(InviteInvalidException.class);
    assertThatThrownBy(() -> useCase.execute("Zzzz9999", user))
        .isInstanceOf(InviteInvalidException.class);
    verify(members, never()).addIfAbsent(any());
  }
}

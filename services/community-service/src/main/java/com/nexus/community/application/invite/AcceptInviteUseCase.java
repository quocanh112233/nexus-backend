package com.nexus.community.application.invite;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.ServerSummary;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case tham gia server bằng lời mời, thêm thành viên và tăng uses trong một transaction
@Service
public class AcceptInviteUseCase {

  private final InviteRepository inviteRepository;
  private final ServerRepository serverRepository;
  private final MemberRepository memberRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các cổng lưu trữ, cổng phát sự kiện và đồng hồ qua constructor
  public AcceptInviteUseCase(
      InviteRepository inviteRepository,
      ServerRepository serverRepository,
      MemberRepository memberRepository,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.inviteRepository = inviteRepository;
    this.serverRepository = serverRepository;
    this.memberRepository = memberRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Thêm member trước rồi tryConsume; đã là member thì trả server, hết lượt thì ném để rollback
  @Transactional
  public ServerSummary execute(String code, UserId userId) {
    if (!InviteCode.isWellFormed(code)) {
      throw new InviteInvalidException();
    }
    Invite invite =
        inviteRepository.findByCode(new InviteCode(code)).orElseThrow(InviteInvalidException::new);
    Server server =
        serverRepository.findById(invite.serverId()).orElseThrow(InviteInvalidException::new);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Member member = Member.join(server.id(), userId, now);
    if (!memberRepository.addIfAbsent(member)) {
      return ServerSummary.from(server);
    }
    if (!inviteRepository.tryConsume(invite.code(), now)) {
      throw new InviteInvalidException();
    }
    member.pullEvents().forEach(eventPublisher::publish);
    return ServerSummary.from(server);
  }
}

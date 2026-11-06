package com.nexus.community.application.invite;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.ServerSummary;
import com.nexus.community.domain.ban.ServerBanRepository;
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

// Use case tham gia server bằng lời mời: khóa cặp, chặn ban, thêm member, tăng uses
@Service
public class AcceptInviteUseCase {

  private final InviteRepository inviteRepository;
  private final ServerRepository serverRepository;
  private final MemberRepository memberRepository;
  private final ServerBanRepository serverBanRepository;
  private final MemberLock memberLock;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các cổng lưu trữ, khóa, phát sự kiện và đồng hồ qua constructor
  public AcceptInviteUseCase(
      InviteRepository inviteRepository,
      ServerRepository serverRepository,
      MemberRepository memberRepository,
      ServerBanRepository serverBanRepository,
      MemberLock memberLock,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.inviteRepository = inviteRepository;
    this.serverRepository = serverRepository;
    this.memberRepository = memberRepository;
    this.serverBanRepository = serverBanRepository;
    this.memberLock = memberLock;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Khóa cặp, chặn ban, thêm member rồi tryConsume; đã là member thì trả server, hết lượt thì ném
  @Transactional
  public ServerSummary execute(String code, UserId userId) {
    if (!InviteCode.isWellFormed(code)) {
      throw new InviteInvalidException();
    }
    Invite invite =
        inviteRepository.findByCode(new InviteCode(code)).orElseThrow(InviteInvalidException::new);
    Server server =
        serverRepository.findById(invite.serverId()).orElseThrow(InviteInvalidException::new);
    memberLock.lock(server.id(), userId);
    if (serverBanRepository.find(server.id(), userId).isPresent()) {
      throw new BannedFromServerException();
    }
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

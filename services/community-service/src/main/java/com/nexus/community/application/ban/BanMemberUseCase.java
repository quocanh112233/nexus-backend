package com.nexus.community.application.ban;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case ban user khỏi server, cần BAN_MEMBERS, đích có thể chưa là thành viên
@Service
public class BanMemberUseCase {

  private final ServerAccess access;
  private final MemberRepository memberRepository;
  private final ServerBanRepository banRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final MemberLock memberLock;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, lưu trữ, khóa, phát sự kiện và đồng hồ qua constructor
  public BanMemberUseCase(
      ServerAccess access,
      MemberRepository memberRepository,
      ServerBanRepository banRepository,
      ChannelOverwriteRepository overwriteRepository,
      MemberLock memberLock,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.access = access;
    this.memberRepository = memberRepository;
    this.banRepository = banRepository;
    this.overwriteRepository = overwriteRepository;
    this.memberLock = memberLock;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Kiểm tra quyền, khóa cặp, thứ bậc rồi thêm ban; đã ban thì bỏ qua, mới thì gỡ member và phát
  @Transactional
  public void execute(ServerId serverId, UserId actorId, UserId targetId, String reasonOrNull) {
    Authorized auth = access.requirePermission(serverId, actorId, Permission.BAN_MEMBERS);
    memberLock.lock(serverId, targetId);
    Member target = memberRepository.findForUpdate(serverId, targetId).orElse(null);
    auth.requireCanModerate(targetId, target);
    ServerBan ban =
        ServerBan.create(
            serverId,
            targetId,
            actorId,
            reasonOrNull,
            target != null,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    if (!banRepository.addIfAbsent(ban)) {
      return;
    }
    if (target != null) {
      memberRepository.remove(serverId, targetId);
    }
    overwriteRepository.removeByTarget(serverId, targetId.value());
    ban.pullEvents().forEach(eventPublisher::publish);
  }
}

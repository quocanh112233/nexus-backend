package com.nexus.community.application.member;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.Authorized;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.channel.ChannelOverwriteRepository;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case kick thành viên, cần KICK_MEMBERS và đích phải thấp hơn actor
@Service
public class KickMemberUseCase {

  private final ServerAccess access;
  private final MemberRepository memberRepository;
  private final ChannelOverwriteRepository overwriteRepository;
  private final MemberLock memberLock;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, lưu trữ, khóa và phát sự kiện qua constructor
  public KickMemberUseCase(
      ServerAccess access,
      MemberRepository memberRepository,
      ChannelOverwriteRepository overwriteRepository,
      MemberLock memberLock,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.memberRepository = memberRepository;
    this.overwriteRepository = overwriteRepository;
    this.memberLock = memberLock;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm tra quyền, khóa cặp, đích và thứ bậc rồi gỡ member, dọn overwrite, phát MemberKicked
  @Transactional
  public void execute(ServerId serverId, UserId actorId, UserId targetId) {
    Authorized auth = access.requirePermission(serverId, actorId, Permission.KICK_MEMBERS);
    memberLock.lock(serverId, targetId);
    Member target =
        memberRepository
            .findForUpdate(serverId, targetId)
            .orElseThrow(MemberNotFoundException::new);
    auth.requireCanModerate(targetId, target);
    target.kick(actorId);
    memberRepository.remove(serverId, targetId);
    overwriteRepository.removeByTarget(serverId, targetId.value());
    target.pullEvents().forEach(eventPublisher::publish);
  }
}

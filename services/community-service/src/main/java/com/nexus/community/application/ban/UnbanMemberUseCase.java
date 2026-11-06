package com.nexus.community.application.ban;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case gỡ ban user, cần BAN_MEMBERS và user phải đang bị ban
@Service
public class UnbanMemberUseCase {

  private final ServerAccess access;
  private final ServerBanRepository banRepository;
  private final MemberLock memberLock;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, lưu trữ, khóa và phát sự kiện qua constructor
  public UnbanMemberUseCase(
      ServerAccess access,
      ServerBanRepository banRepository,
      MemberLock memberLock,
      DomainEventPublisher eventPublisher) {
    this.access = access;
    this.banRepository = banRepository;
    this.memberLock = memberLock;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm tra quyền, khóa cặp, gỡ ban và phát MemberUnbanned, không bị ban thì 404
  @Transactional
  public void execute(ServerId serverId, UserId actorId, UserId targetId) {
    access.requirePermission(serverId, actorId, Permission.BAN_MEMBERS);
    memberLock.lock(serverId, targetId);
    ServerBan ban = banRepository.find(serverId, targetId).orElseThrow(BanNotFoundException::new);
    ban.lift(actorId);
    banRepository.remove(serverId, targetId);
    ban.pullEvents().forEach(eventPublisher::publish);
  }
}

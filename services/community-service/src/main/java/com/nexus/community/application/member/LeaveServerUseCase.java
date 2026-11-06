package com.nexus.community.application.member;

import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.application.server.PermissionDeniedException;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.application.server.ServerNotFoundException;
import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case rời server, chủ server không được rời và phải xóa server
@Service
public class LeaveServerUseCase {

  private final ServerAccess access;
  private final MemberRepository memberRepository;
  private final DomainEventPublisher eventPublisher;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và cổng phát sự kiện qua constructor
  public LeaveServerUseCase(
      ServerAccess access, MemberRepository memberRepository, DomainEventPublisher eventPublisher) {
    this.access = access;
    this.memberRepository = memberRepository;
    this.eventPublisher = eventPublisher;
  }

  // Kiểm tra là thành viên và không phải chủ, phát MemberLeft rồi gỡ thành viên
  @Transactional
  public void execute(ServerId serverId, UserId userId) {
    Server server = access.requireMember(serverId, userId);
    if (server.isOwner(userId)) {
      throw new PermissionDeniedException("Owner cannot leave; delete the server");
    }
    Member member =
        memberRepository.find(serverId, userId).orElseThrow(ServerNotFoundException::new);
    member.leave();
    member.pullEvents().forEach(eventPublisher::publish);
    memberRepository.remove(serverId, userId);
  }
}

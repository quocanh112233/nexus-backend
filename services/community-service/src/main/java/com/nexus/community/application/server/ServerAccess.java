package com.nexus.community.application.server;

import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Component;

// Cổng kiểm tra quyền truy cập server cho các use case, chạy trong transaction của use case
@Component
public class ServerAccess {

  private final ServerRepository serverRepository;
  private final MemberRepository memberRepository;

  // Nhận cổng lưu trữ server và thành viên qua constructor
  public ServerAccess(ServerRepository serverRepository, MemberRepository memberRepository) {
    this.serverRepository = serverRepository;
    this.memberRepository = memberRepository;
  }

  // Trả server nếu user là thành viên, server không có hoặc không phải thành viên thì NotFound
  public Server requireMember(ServerId serverId, UserId userId) {
    Server server = serverRepository.findById(serverId).orElseThrow(ServerNotFoundException::new);
    if (memberRepository.find(serverId, userId).isEmpty()) {
      throw new ServerNotFoundException();
    }
    return server;
  }

  // Trả server nếu user là chủ, thành viên không phải chủ thì PermissionDenied
  public Server requireOwner(ServerId serverId, UserId userId) {
    Server server = requireMember(serverId, userId);
    if (!server.isOwner(userId)) {
      throw new PermissionDeniedException();
    }
    return server;
  }
}

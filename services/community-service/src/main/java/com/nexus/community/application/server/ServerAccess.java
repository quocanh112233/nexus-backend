package com.nexus.community.application.server;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Actor;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Component;

// Cổng kiểm tra quyền truy cập server cho các use case, tự nó không mở transaction
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

  // Trả server, member, actor của thành viên, ngoài server thì NotFound
  public Authorized requireAuthorized(ServerId serverId, UserId userId) {
    Server server = serverRepository.findById(serverId).orElseThrow(ServerNotFoundException::new);
    Member member =
        memberRepository.find(serverId, userId).orElseThrow(ServerNotFoundException::new);
    return new Authorized(server, member, Actor.of(server, member));
  }

  // Trả server, member, actor nếu user có quyền, ngoài server thì NotFound, thiếu quyền thì Denied
  public Authorized requirePermission(ServerId serverId, UserId userId, Permission permission) {
    Authorized auth = requireAuthorized(serverId, userId);
    if (!auth.actor().serverPermissions().has(permission)) {
      throw new PermissionDeniedException();
    }
    return auth;
  }
}

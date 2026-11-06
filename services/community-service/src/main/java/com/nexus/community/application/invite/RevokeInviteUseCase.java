package com.nexus.community.application.invite;

import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case thu hồi lời mời, chỉ người tạo hoặc người có quyền MANAGE_SERVER
@Service
public class RevokeInviteUseCase {

  private final ServerAccess access;
  private final InviteRepository inviteRepository;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ và đồng hồ qua constructor
  public RevokeInviteUseCase(ServerAccess access, InviteRepository inviteRepository, Clock clock) {
    this.access = access;
    this.inviteRepository = inviteRepository;
    this.clock = clock;
  }

  // Đặt revoked_at rồi lưu; người ngoài hoặc mã lạ là NotFound, thành viên khác là PermissionDenied
  @Transactional
  public void execute(String code, UserId userId) {
    if (!InviteCode.isWellFormed(code)) {
      throw new InviteNotFoundException();
    }
    Invite invite =
        inviteRepository.findByCode(new InviteCode(code)).orElseThrow(InviteNotFoundException::new);
    if (invite.inviterId().equals(userId)) {
      access.requireMember(invite.serverId(), userId);
    } else {
      access.requirePermission(invite.serverId(), userId, Permission.MANAGE_SERVER);
    }
    invite.revoke(clock.instant().truncatedTo(ChronoUnit.MICROS));
    inviteRepository.save(invite);
  }
}

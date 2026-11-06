package com.nexus.community.application.invite;

import com.nexus.community.application.port.InviteCodeGenerator;
import com.nexus.community.application.server.ServerAccess;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// Use case tạo lời mời, mọi thành viên server được phép
@Service
public class CreateInviteUseCase {

  private static final int MAX_ATTEMPTS = 3;

  private final ServerAccess access;
  private final InviteRepository inviteRepository;
  private final InviteCodeGenerator codeGenerator;
  private final Clock clock;

  // Nhận cổng kiểm tra quyền, cổng lưu trữ, bộ sinh mã và đồng hồ qua constructor
  public CreateInviteUseCase(
      ServerAccess access,
      InviteRepository inviteRepository,
      InviteCodeGenerator codeGenerator,
      Clock clock) {
    this.access = access;
    this.inviteRepository = inviteRepository;
    this.codeGenerator = codeGenerator;
    this.clock = clock;
  }

  // Tạo lời mời, thử tối đa 3 mã nếu trùng; không bọc transaction để INSERT trùng còn thử lại được
  public InviteView execute(ServerId serverId, UserId userId, int maxUses, long expiresInSeconds) {
    access.requireMember(serverId, userId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      InviteCode code = codeGenerator.generate();
      if (inviteRepository.findByCode(code).isPresent()) {
        continue;
      }
      Invite invite =
          Invite.create(code, serverId, userId, maxUses, now.plusSeconds(expiresInSeconds), now);
      try {
        inviteRepository.add(invite);
        return InviteView.from(invite);
      } catch (DataIntegrityViolationException e) {
        continue;
      }
    }
    throw new IllegalStateException("could not generate a unique invite code");
  }
}

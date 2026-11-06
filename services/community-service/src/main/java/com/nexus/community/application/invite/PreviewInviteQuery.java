package com.nexus.community.application.invite;

import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn xem trước lời mời công khai, không cần đăng nhập
@Service
public class PreviewInviteQuery {

  private final InviteRepository inviteRepository;
  private final ServerRepository serverRepository;
  private final MemberRepository memberRepository;
  private final Clock clock;

  // Nhận các cổng lưu trữ và đồng hồ qua constructor
  public PreviewInviteQuery(
      InviteRepository inviteRepository,
      ServerRepository serverRepository,
      MemberRepository memberRepository,
      Clock clock) {
    this.inviteRepository = inviteRepository;
    this.serverRepository = serverRepository;
    this.memberRepository = memberRepository;
    this.clock = clock;
  }

  // Trả xem trước nếu lời mời còn dùng được, ngược lại InviteNotFound
  @Transactional(readOnly = true)
  public InvitePreview execute(String code) {
    if (!InviteCode.isWellFormed(code)) {
      throw new InviteNotFoundException();
    }
    Invite invite =
        inviteRepository
            .findByCode(new InviteCode(code))
            .filter(i -> i.isUsable(clock.instant()))
            .orElseThrow(InviteNotFoundException::new);
    Server server =
        serverRepository.findById(invite.serverId()).orElseThrow(InviteNotFoundException::new);
    return new InvitePreview(
        code, server.name().value(), memberRepository.count(server.id()), invite.expiresAt());
  }
}

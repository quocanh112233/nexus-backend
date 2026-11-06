package com.nexus.community.application.invite;

import com.nexus.community.domain.invite.Invite;
import java.time.Instant;
import java.util.UUID;

// Góc nhìn một lời mời cho người tạo và chủ server
public record InviteView(
    String code, UUID serverId, UUID inviterId, int maxUses, int uses, Instant expiresAt) {

  // Dựng góc nhìn từ aggregate lời mời
  static InviteView from(Invite invite) {
    return new InviteView(
        invite.code().value(),
        invite.serverId().value(),
        invite.inviterId().value(),
        invite.maxUses(),
        invite.uses(),
        invite.expiresAt());
  }
}

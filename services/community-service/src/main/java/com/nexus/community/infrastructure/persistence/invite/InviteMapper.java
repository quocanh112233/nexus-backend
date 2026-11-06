package com.nexus.community.infrastructure.persistence.invite;

import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate Invite và InviteJpaEntity
@Component
final class InviteMapper {

  // Dựng lại aggregate từ entity qua Invite.rehydrate
  Invite toDomain(InviteJpaEntity e) {
    return Invite.rehydrate(
        new InviteCode(e.code),
        new ServerId(e.serverId),
        new UserId(e.inviterId),
        e.maxUses,
        e.uses,
        e.expiresAt,
        e.revokedAt,
        e.createdAt);
  }

  // Dựng entity từ aggregate
  InviteJpaEntity toJpa(Invite i) {
    InviteJpaEntity e = new InviteJpaEntity();
    e.code = i.code().value();
    e.serverId = i.serverId().value();
    e.inviterId = i.inviterId().value();
    e.maxUses = i.maxUses();
    e.uses = i.uses();
    e.expiresAt = i.expiresAt();
    e.revokedAt = i.revokedAt();
    e.createdAt = i.createdAt();
    return e;
  }
}

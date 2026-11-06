package com.nexus.community.interfaces.rest;

import com.nexus.community.application.invite.InviteView;
import java.time.Instant;
import java.util.UUID;

// Phản hồi một lời mời
public record InviteResponse(
    String code, UUID serverId, UUID inviterId, int maxUses, int uses, Instant expiresAt) {

  // Dựng phản hồi từ góc nhìn lời mời
  static InviteResponse from(InviteView v) {
    return new InviteResponse(
        v.code(), v.serverId(), v.inviterId(), v.maxUses(), v.uses(), v.expiresAt());
  }
}

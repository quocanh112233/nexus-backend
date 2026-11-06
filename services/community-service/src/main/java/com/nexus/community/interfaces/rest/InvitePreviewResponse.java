package com.nexus.community.interfaces.rest;

import com.nexus.community.application.invite.InvitePreview;
import java.time.Instant;

// Phản hồi xem trước lời mời công khai
public record InvitePreviewResponse(
    String code, String serverName, long memberCount, Instant expiresAt) {

  // Dựng phản hồi từ xem trước lời mời
  static InvitePreviewResponse from(InvitePreview p) {
    return new InvitePreviewResponse(p.code(), p.serverName(), p.memberCount(), p.expiresAt());
  }
}

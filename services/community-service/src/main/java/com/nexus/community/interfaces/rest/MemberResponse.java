package com.nexus.community.interfaces.rest;

import com.nexus.community.application.member.MemberView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Phản hồi một thành viên
public record MemberResponse(UUID userId, Instant joinedAt, List<UUID> roleIds) {

  // Dựng phản hồi từ góc nhìn thành viên
  static MemberResponse from(MemberView v) {
    return new MemberResponse(v.userId(), v.joinedAt(), v.roleIds());
  }
}

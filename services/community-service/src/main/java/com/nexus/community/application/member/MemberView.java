package com.nexus.community.application.member;

import com.nexus.community.domain.member.Member;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Góc nhìn một thành viên của server
public record MemberView(UUID userId, Instant joinedAt, List<UUID> roleIds) {

  // Dựng góc nhìn từ aggregate thành viên
  static MemberView from(Member member) {
    return new MemberView(
        member.userId().value(),
        member.joinedAt(),
        member.roleIds().stream().map(r -> r.value()).toList());
  }
}

package com.nexus.community.domain.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm thử sự kiện của aggregate Member
class MemberTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Kịch bản: vào server phát MemberJoined mang thời điểm vào
  // Input: Member.join(serverId, userId, NOW)
  // Output: 1 MemberJoined với joinedAt = NOW, roleIds rỗng
  @Test
  void joinEmitsMemberJoinedWithTime() {
    ServerId serverId = ServerId.newId();
    UserId userId = UserId.newId();

    Member member = Member.join(serverId, userId, NOW);

    assertThat(member.pullEvents())
        .singleElement()
        .isEqualTo(new MemberJoined(serverId, userId, NOW));
    assertThat(member.roleIds()).isEmpty();
  }

  // Kịch bản: rời server phát MemberLeft
  // Input: rehydrate rồi leave()
  // Output: 1 MemberLeft với serverId và userId
  @Test
  void leaveEmitsMemberLeft() {
    ServerId serverId = ServerId.newId();
    UserId userId = UserId.newId();
    Member member = Member.rehydrate(serverId, userId, NOW);

    member.leave();

    assertThat(member.pullEvents()).singleElement().isEqualTo(new MemberLeft(serverId, userId));
  }
}

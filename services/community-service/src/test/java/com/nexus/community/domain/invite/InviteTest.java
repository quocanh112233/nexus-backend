package com.nexus.community.domain.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm thử điều kiện sử dụng của aggregate Invite
class InviteTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(3600);

  // Dựng lời mời đã dùng uses lượt trên tổng maxUses
  private static Invite invite(int maxUses, int uses, Instant revokedAt) {
    return Invite.rehydrate(
        new InviteCode("abcd1234"),
        ServerId.newId(),
        UserId.newId(),
        maxUses,
        uses,
        EXPIRES,
        revokedAt,
        NOW);
  }

  // Kịch bản: lời mời mới còn dùng được trong giới hạn và có uses = 0
  // Input: create(maxUses 5, hạn NOW+1h) kiểm tra tại NOW và tại NOW+1h-1s
  // Output: usable cả hai lần, uses 0
  @Test
  void usableWithinLimits() {
    Invite invite =
        Invite.create(
            new InviteCode("abcd1234"), ServerId.newId(), UserId.newId(), 5, EXPIRES, NOW);

    assertThat(invite.isUsable(NOW)).isTrue();
    assertThat(invite.isUsable(EXPIRES.minusSeconds(1))).isTrue();
    assertThat(invite.uses()).isZero();
  }

  // Kịch bản: hết hạn thì không dùng được, và create từ chối hạn không ở tương lai hoặc maxUses âm
  // Input: isUsable tại đúng thời điểm hết hạn; create với hạn = now; create với maxUses -1
  // Output: false; hai lần create ném DomainValidationException
  @Test
  void unusableWhenExpired() {
    assertThat(invite(0, 0, null).isUsable(EXPIRES)).isFalse();
    assertThatThrownBy(
            () ->
                Invite.create(
                    new InviteCode("abcd1234"), ServerId.newId(), UserId.newId(), 0, NOW, NOW))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(
            () ->
                Invite.create(
                    new InviteCode("abcd1234"), ServerId.newId(), UserId.newId(), -1, EXPIRES, NOW))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: đã thu hồi thì không dùng được
  // Input: create rồi revoke(NOW)
  // Output: isUsable false, revokedAt = NOW
  @Test
  void unusableWhenRevoked() {
    Invite invite =
        Invite.create(
            new InviteCode("abcd1234"), ServerId.newId(), UserId.newId(), 0, EXPIRES, NOW);

    invite.revoke(NOW);

    assertThat(invite.isUsable(NOW)).isFalse();
    assertThat(invite.revokedAt()).isEqualTo(NOW);
  }

  // Kịch bản: đã dùng hết lượt thì không dùng được
  // Input: maxUses 3 với uses 3, và uses 2
  // Output: false cho uses 3, true cho uses 2
  @Test
  void unusableWhenExhausted() {
    assertThat(invite(3, 3, null).isUsable(NOW)).isFalse();
    assertThat(invite(3, 2, null).isUsable(NOW)).isTrue();
  }

  // Kịch bản: maxUses = 0 là không giới hạn lượt
  // Input: maxUses 0 với uses 1000
  // Output: isUsable true
  @Test
  void maxUsesZeroIsUnlimited() {
    assertThat(invite(0, 1000, null).isUsable(NOW)).isTrue();
  }
}

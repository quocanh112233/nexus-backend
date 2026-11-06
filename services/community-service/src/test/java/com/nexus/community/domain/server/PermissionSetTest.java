package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.domain.shared.DomainValidationException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

// Kiểm thử bitset Permission và PermissionSet
class PermissionSetTest {

  // Kịch bản: mặc định của @everyone có đúng 6 quyền và không có quyền quản trị
  // Input: PermissionSet.EVERYONE_DEFAULT
  // Output: có 6 quyền theo tài liệu, không có MANAGE_SERVER và KICK_MEMBERS
  @Test
  void everyoneDefault() {
    PermissionSet set = PermissionSet.EVERYONE_DEFAULT;
    long count = Arrays.stream(Permission.values()).filter(set::has).count();
    assertThat(count).isEqualTo(6);
    assertThat(set.has(Permission.CREATE_INVITE)).isTrue();
    assertThat(set.has(Permission.ADD_REACTIONS)).isTrue();
    assertThat(set.has(Permission.MANAGE_SERVER)).isFalse();
    assertThat(set.has(Permission.KICK_MEMBERS)).isFalse();
  }

  // Kịch bản: with và without trả tập mới, tập gốc không đổi
  // Input: tập rỗng with MANAGE_ROLES rồi without MANAGE_ROLES
  // Output: tập gốc vẫn rỗng, tập with có quyền, tập without hết quyền
  @Test
  void immutable() {
    PermissionSet empty = new PermissionSet(0);
    PermissionSet added = empty.with(Permission.MANAGE_ROLES);
    assertThat(empty.has(Permission.MANAGE_ROLES)).isFalse();
    assertThat(added.has(Permission.MANAGE_ROLES)).isTrue();
    assertThat(added.without(Permission.MANAGE_ROLES)).isEqualTo(empty);
  }

  // Kịch bản: ALL chứa mọi quyền và mặt nạ khớp số bit
  // Input: PermissionSet.ALL và Permission.BAN_MEMBERS
  // Output: ALL.has đúng với mọi quyền, BAN_MEMBERS.mask() là 1L << 12
  @Test
  void allAndMask() {
    for (Permission p : Permission.values()) {
      assertThat(PermissionSet.ALL.has(p)).isTrue();
    }
    assertThat(Permission.values()).hasSize(13);
    assertThat(Permission.BAN_MEMBERS.mask()).isEqualTo(1L << 12);
    assertThat(Permission.ADMINISTRATOR.mask()).isEqualTo(1L);
  }

  // Kịch bản: CHANNEL_SCOPE gồm đúng các quyền bit 2 đến 10
  // Input: isChannelScoped với từng Permission
  // Output: true với bit 2..10, false với ADMINISTRATOR, MANAGE_SERVER, KICK_MEMBERS, BAN_MEMBERS
  @Test
  void channelScope() {
    for (Permission p : Permission.values()) {
      int bit = Long.numberOfTrailingZeros(p.mask());
      assertThat(PermissionSet.isChannelScoped(p)).isEqualTo(bit >= 2 && bit <= 10);
    }
    assertThat(PermissionSet.CHANNEL_SCOPE.bits()).isEqualTo(0b11111111100L);
  }

  // Kịch bản: contains kiểm tập con, union, minus, intersects
  // Input: a = {KICK, BAN}, b = {KICK}, c = {MANAGE_ROLES}
  // Output: a chứa b không chứa c, union gộp, minus bỏ, intersects đúng với b và sai với c
  @Test
  void setAlgebra() {
    PermissionSet a = new PermissionSet(0).with(Permission.KICK_MEMBERS, Permission.BAN_MEMBERS);
    PermissionSet b = new PermissionSet(0).with(Permission.KICK_MEMBERS);
    PermissionSet c = new PermissionSet(0).with(Permission.MANAGE_ROLES);
    assertThat(a.contains(b)).isTrue();
    assertThat(a.contains(c)).isFalse();
    assertThat(b.union(c).bits()).isEqualTo(b.bits() | c.bits());
    assertThat(a.minus(b)).isEqualTo(new PermissionSet(0).with(Permission.BAN_MEMBERS));
    assertThat(a.intersects(b)).isTrue();
    assertThat(a.intersects(c)).isFalse();
  }

  // Kịch bản: ofBits nhận giá trị hợp lệ
  // Input: ofBits(0) và ofBits(ALL.bits())
  // Output: rỗng và ALL
  @Test
  void ofBitsValid() {
    assertThat(PermissionSet.ofBits(0)).isEqualTo(new PermissionSet(0));
    assertThat(PermissionSet.ofBits(PermissionSet.ALL.bits())).isEqualTo(PermissionSet.ALL);
  }

  // Kịch bản: ofBits từ chối bit âm hoặc bit lạ ngoài ALL
  // Input: ofBits(-1) và ofBits(1L << 13)
  // Output: ném DomainValidationException
  @Test
  void ofBitsRejectsInvalid() {
    assertThatThrownBy(() -> PermissionSet.ofBits(-1))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> PermissionSet.ofBits(1L << 13))
        .isInstanceOf(DomainValidationException.class);
  }
}

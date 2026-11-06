package com.nexus.community.domain.server;

import static org.assertj.core.api.Assertions.assertThat;

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
}

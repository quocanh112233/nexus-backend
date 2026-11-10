package com.nexus.messaging.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra cặp người dùng có thứ tự chuẩn hóa
class UserPairTest {

  private final UserId a = new UserId(UUID.randomUUID());
  private final UserId b = new UserId(UUID.randomUUID());

  // Kịch bản: hai thứ tự đối số cho cùng một cặp và low nhỏ hơn high
  // Input: of(a, b) và of(b, a)
  // Output: hai cặp bằng nhau, contains và other đúng
  @Test
  void normalizesOrder() {
    UserPair p1 = UserPair.of(a, b);
    UserPair p2 = UserPair.of(b, a);

    assertThat(p1).isEqualTo(p2);
    assertThat(p1.contains(a)).isTrue();
    assertThat(p1.contains(new UserId(UUID.randomUUID()))).isFalse();
    assertThat(p1.other(a)).isEqualTo(b);
    assertThat(p1.other(b)).isEqualTo(a);
  }

  // Kịch bản: cặp với chính mình
  // Input: of(a, a)
  // Output: DomainValidationException
  @Test
  void rejectsSameUser() {
    assertThatThrownBy(() -> UserPair.of(a, a)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: thứ tự phải khớp uuid của PostgreSQL (so byte không dấu), khác UUID.compareTo
  // Input: of(ffffffff-..., 00000000-...)
  // Output: low là 00000000-..., high là ffffffff-...
  @Test
  void ordersLikePostgresUnsigned() {
    UserId zero = new UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    UserId ff = new UserId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

    UserPair pair = UserPair.of(ff, zero);

    assertThat(pair.low()).isEqualTo(zero);
    assertThat(pair.high()).isEqualTo(ff);
  }

  // Kịch bản: gọi thẳng constructor bỏ qua chuẩn hóa
  // Input: new UserPair(high, low) và new UserPair(a, a)
  // Output: DomainValidationException cho cả hai
  @Test
  void canonicalConstructorEnforcesOrder() {
    UserPair pair = UserPair.of(a, b);

    assertThatThrownBy(() -> new UserPair(pair.high(), pair.low()))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new UserPair(a, a)).isInstanceOf(DomainValidationException.class);
  }
}

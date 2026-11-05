package com.nexus.identity.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.MediaRef;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.Username;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// Kiểm tra UserMapper chuyển đổi hai chiều không mất field
class UserMapperTest {

  private final UserMapper mapper = new UserMapper();

  // Kịch bản: user có avatar đi vòng qua entity JPA rồi về domain
  // Input: user rehydrate với avatar non-null và version 3
  // Output: toDomain(toJpa(user)) bằng user từng field
  @Test
  void roundTripKeepsAllFields() {
    User user = sample(new MediaRef("pub-1", "https://cdn.x/a.png"), 3L);

    User back = mapper.toDomain(mapper.toJpa(user));

    assertThat(back).usingRecursiveComparison().isEqualTo(user);
  }

  // Kịch bản: user chưa có avatar và chưa lưu đi vòng qua entity JPA
  // Input: user rehydrate với avatar null và version null
  // Output: toDomain(toJpa(user)) bằng user, avatar và version đều null
  @Test
  void roundTripKeepsNullAvatarAndNullVersion() {
    User user = sample(null, null);

    User back = mapper.toDomain(mapper.toJpa(user));

    assertThat(back).usingRecursiveComparison().isEqualTo(user);
    assertThat(back.avatar()).isNull();
    assertThat(back.version()).isNull();
  }

  // Dựng user mẫu qua rehydrate với avatar và version cho trước
  private static User sample(MediaRef avatar, Long version) {
    return User.rehydrate(
        UserId.newId(),
        new Email("a@x.com"),
        new Username("alice"),
        new DisplayName("Alice"),
        new AboutMe("hello"),
        new PasswordHash("$2a$12$hash"),
        avatar,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-02T00:00:00Z"),
        version);
  }
}

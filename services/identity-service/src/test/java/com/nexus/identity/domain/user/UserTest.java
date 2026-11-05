package com.nexus.identity.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// Kiểm thử hành vi và sự kiện của aggregate User
class UserTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Dựng một User hợp lệ với dữ liệu mẫu cố định
  private static User newUser() {
    return User.register(
        UserId.newId(),
        new Email("a@x.com"),
        new Username("quoc_anh"),
        new DisplayName("Quoc Anh"),
        new PasswordHash("hash"),
        NOW);
  }

  // Kịch bản: đăng ký phát UserRegistered đúng một lần
  // Input: User.register rồi gọi pullEvents hai lần
  // Output: lần 1 có đúng 1 UserRegistered, lần 2 rỗng
  @Test
  void registerEmitsUserRegisteredOnce() {
    User user = newUser();

    List<DomainEvent> first = user.pullEvents();

    assertThat(first).hasSize(1).first().isInstanceOf(UserRegistered.class);
    assertThat(user.pullEvents()).isEmpty();
    assertThat(user.version()).isNull();
  }

  // Kịch bản: cập nhật hồ sơ phát UserProfileUpdated mang giá trị mới
  // Input: updateProfile(DisplayName "New", AboutMe "hello")
  // Output: sự kiện chứa displayName "New" và aboutMe "hello"
  @Test
  void updateProfileEmitsEventWithNewValues() {
    User user = newUser();
    user.pullEvents();

    user.updateProfile(new DisplayName("New"), new AboutMe("hello"), NOW.plusSeconds(5));

    assertThat(user.pullEvents())
        .singleElement()
        .isEqualTo(
            new UserProfileUpdated(user.id(), new DisplayName("New"), null, new AboutMe("hello")));
    assertThat(user.updatedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  // Kịch bản: đổi avatar phát UserProfileUpdated kèm avatarUrl
  // Input: changeAvatar(MediaRef "pid", "https://cdn/x.png")
  // Output: sự kiện có avatarUrl "https://cdn/x.png"
  @Test
  void changeAvatarEmitsEventWithAvatarUrl() {
    User user = newUser();
    user.pullEvents();

    user.changeAvatar(new MediaRef("pid", "https://cdn/x.png"), NOW.plusSeconds(5));

    assertThat(user.pullEvents())
        .singleElement()
        .isEqualTo(
            new UserProfileUpdated(
                user.id(), new DisplayName("Quoc Anh"), "https://cdn/x.png", new AboutMe(null)));
  }
}

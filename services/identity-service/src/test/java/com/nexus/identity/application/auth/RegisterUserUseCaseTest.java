package com.nexus.identity.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.application.port.PasswordHasher;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.RawPassword;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserRegistered;
import com.nexus.identity.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra RegisterUserUseCase với repository, hasher và publisher được mock
class RegisterUserUseCaseTest {

  private final UserRepository repository = mock(UserRepository.class);
  private final PasswordHasher hasher = mock(PasswordHasher.class);
  private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final RegisterUserUseCase useCase =
      new RegisterUserUseCase(repository, hasher, publisher, clock);

  // Kịch bản: user lưu xuống chỉ mang hash, không mang mật khẩu thô
  // Input: đăng ký với mật khẩu "secret-pass-1", hasher trả "HASHED"
  // Output: user truyền vào save có passwordHash "HASHED"
  @Test
  void savesHashedPasswordNeverRaw() {
    stubHasherAndRepository();

    useCase.execute(new RegisterCommand("a@b.co", "alice", "Alice", "secret-pass-1"));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().passwordHash().value()).isEqualTo("HASHED");
    verify(hasher).hash(new RawPassword("secret-pass-1"));
  }

  // Kịch bản: đăng ký xong thì publish đúng sự kiện UserRegistered
  // Input: đăng ký alice với tên hiển thị Alice
  // Output: publisher nhận một UserRegistered mang username alice
  @Test
  void publishesUserRegistered() {
    stubHasherAndRepository();

    useCase.execute(new RegisterCommand("a@b.co", "alice", "Alice", "secret-pass-1"));

    ArgumentCaptor<UserRegistered> event = ArgumentCaptor.forClass(UserRegistered.class);
    verify(publisher).publish(event.capture());
    assertThat(event.getValue().username().value()).isEqualTo("alice");
    assertThat(event.getValue().displayName().value()).isEqualTo("Alice");
  }

  // Kịch bản: không gửi displayName thì dùng username
  // Input: đăng ký với displayName null và username bob
  // Output: user truyền vào save có displayName "bob"
  @Test
  void displayNameDefaultsToUsername() {
    stubHasherAndRepository();

    useCase.execute(new RegisterCommand("b@b.co", "bob", null, "secret-pass-1"));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().displayName().value()).isEqualTo("bob");
  }

  // Dựng hasher trả "HASHED" và repository trả lại đúng user được lưu
  private void stubHasherAndRepository() {
    when(hasher.hash(any())).thenReturn(new PasswordHash("HASHED"));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }
}

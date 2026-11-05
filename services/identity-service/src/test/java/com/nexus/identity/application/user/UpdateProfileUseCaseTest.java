package com.nexus.identity.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserProfileUpdated;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra UpdateProfileUseCase với repository và publisher được mock
class UpdateProfileUseCaseTest {

  private final UserRepository repository = mock(UserRepository.class);
  private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
  private final UpdateProfileUseCase useCase =
      new UpdateProfileUseCase(repository, publisher, clock);

  // Kịch bản: chỉ field được gửi bị đổi và phát đúng một UserProfileUpdated
  // Input: user có displayName Old, aboutMe "old bio"; cập nhật displayName null, aboutMe "new bio"
  // Output: save nhận displayName Old và aboutMe "new bio"; publisher nhận event mang giá trị mới
  @Test
  void updatesOnlyProvidedFieldsAndPublishesEvent() {
    UserId id = UserId.newId();
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    User existing =
        User.rehydrate(
            id,
            new Email("a@b.co"),
            new Username("alice"),
            new DisplayName("Old"),
            new AboutMe("old bio"),
            new PasswordHash("H"),
            null,
            created,
            created,
            3L);
    when(repository.findById(id)).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    useCase.execute(id.value(), null, "new bio");

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().displayName().value()).isEqualTo("Old");
    assertThat(saved.getValue().aboutMe().value()).isEqualTo("new bio");
    assertThat(saved.getValue().updatedAt()).isEqualTo(clock.instant());
    ArgumentCaptor<UserProfileUpdated> event = ArgumentCaptor.forClass(UserProfileUpdated.class);
    verify(publisher).publish(event.capture());
    assertThat(event.getValue().displayName().value()).isEqualTo("Old");
    assertThat(event.getValue().aboutMe().value()).isEqualTo("new bio");
  }
}

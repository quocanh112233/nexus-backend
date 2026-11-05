package com.nexus.identity.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.application.port.UploadSigner;
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

// Kiểm tra ChangeAvatarUseCase với repository, publisher và signer giả
class ChangeAvatarUseCaseTest {

  private final UserRepository repository = mock(UserRepository.class);
  private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
  private final UploadSigner signer = mock(UploadSigner.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
  private final ChangeAvatarUseCase useCase =
      new ChangeAvatarUseCase(repository, publisher, signer, clock);
  private final UserId id = UserId.newId();

  // Kịch bản: publicId nằm trong folder của user khác bị từ chối
  // Input: publicId bắt đầu bằng nexus-dev/avatars/{userKhác}/
  // Output: InvalidMediaException, không save, không publish
  @Test
  void rejectsOtherUsersPublicId() {
    when(signer.folderRoot()).thenReturn("nexus-dev");
    String other = "nexus-dev/avatars/" + UserId.newId().value() + "/x";

    assertThatThrownBy(() -> useCase.execute(id.value(), other))
        .isInstanceOf(InvalidMediaException.class);

    verify(repository, never()).save(any());
    verify(publisher, never()).publish(any());
  }

  // Kịch bản: publicId chứa .., ký tự lạ, quá dài hoặc chỉ có tiền tố bị từ chối
  // Input: folder của user kèm ../, dấu cách, 256 ký tự, hoặc không có tên file
  // Output: mỗi trường hợp ném InvalidMediaException
  @Test
  void rejectsPathTraversal() {
    when(signer.folderRoot()).thenReturn("nexus-dev");
    String own = "nexus-dev/avatars/" + id.value() + "/";

    for (String bad :
        new String[] {own + "../x", own + "a b", own + "x".repeat(256 - own.length()), own, null}) {
      assertThatThrownBy(() -> useCase.execute(id.value(), bad))
          .isInstanceOf(InvalidMediaException.class);
    }
    verify(repository, never()).save(any());
  }

  // Kịch bản: publicId hợp lệ được lưu cùng url công khai và phát UserProfileUpdated
  // Input: publicId nexus-dev/avatars/{userId}/pic, signer trả url giả
  // Output: save nhận avatar publicId+url đúng, publisher nhận event mang avatarUrl
  @Test
  void savesMediaRefWithPublicUrl() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    User existing =
        User.rehydrate(
            id,
            new Email("a@b.co"),
            new Username("alice"),
            new DisplayName("Alice"),
            new AboutMe(""),
            new PasswordHash("H"),
            null,
            created,
            created,
            0L);
    String publicId = "nexus-dev/avatars/" + id.value() + "/pic";
    when(signer.folderRoot()).thenReturn("nexus-dev");
    when(signer.publicUrl(publicId)).thenReturn("https://cdn.test/" + publicId);
    when(repository.findById(id)).thenReturn(Optional.of(existing));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    useCase.execute(id.value(), publicId);

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().avatar().publicId()).isEqualTo(publicId);
    assertThat(saved.getValue().avatar().url()).isEqualTo("https://cdn.test/" + publicId);
    ArgumentCaptor<UserProfileUpdated> event = ArgumentCaptor.forClass(UserProfileUpdated.class);
    verify(publisher).publish(event.capture());
    assertThat(event.getValue().avatarUrl()).isEqualTo("https://cdn.test/" + publicId);
  }
}

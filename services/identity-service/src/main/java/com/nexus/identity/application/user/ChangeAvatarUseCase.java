package com.nexus.identity.application.user;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.application.port.UploadSigner;
import com.nexus.identity.domain.shared.DomainEvent;
import com.nexus.identity.domain.user.MediaRef;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đổi avatar: chỉ nhận publicId trong folder của người gọi
@Service
public class ChangeAvatarUseCase {

  private static final int MAX_PUBLIC_ID = 255;
  private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9/_.-]+");

  private final UserRepository userRepository;
  private final DomainEventPublisher eventPublisher;
  private final UploadSigner signer;
  private final Clock clock;

  // Nhận các cổng qua constructor
  public ChangeAvatarUseCase(
      UserRepository userRepository,
      DomainEventPublisher eventPublisher,
      UploadSigner signer,
      Clock clock) {
    this.userRepository = userRepository;
    this.eventPublisher = eventPublisher;
    this.signer = signer;
    this.clock = clock;
  }

  // Kiểm tra publicId rồi đổi avatar và ghi event cùng transaction
  @Transactional
  public User execute(UUID userId, String publicId) {
    String prefix = signer.folderRoot() + "/avatars/" + userId + "/";
    if (publicId == null
        || publicId.length() > MAX_PUBLIC_ID
        || publicId.length() <= prefix.length()
        || !publicId.startsWith(prefix)
        || publicId.contains("..")
        || !ALLOWED.matcher(publicId).matches()) {
      throw new InvalidMediaException();
    }
    User user = userRepository.findById(new UserId(userId)).orElseThrow(UserNotFoundException::new);
    user.changeAvatar(
        new MediaRef(publicId, signer.publicUrl(publicId)),
        clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<DomainEvent> events = user.pullEvents();
    User saved = userRepository.save(user);
    events.forEach(eventPublisher::publish);
    return saved;
  }
}

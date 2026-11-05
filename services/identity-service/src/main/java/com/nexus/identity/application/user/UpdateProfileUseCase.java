package com.nexus.identity.application.user;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.domain.shared.DomainEvent;
import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case cập nhật hồ sơ: chỉ đổi field được gửi, lưu rồi phát UserProfileUpdated
@Service
public class UpdateProfileUseCase {

  private final UserRepository userRepository;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các cổng qua constructor
  public UpdateProfileUseCase(
      UserRepository userRepository, DomainEventPublisher eventPublisher, Clock clock) {
    this.userRepository = userRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Cập nhật field khác null và ghi event cùng transaction, xung đột phiên bản nổi lên từ save
  @Transactional
  public User execute(UUID userId, String displayName, String aboutMe) {
    User user = userRepository.findById(new UserId(userId)).orElseThrow(UserNotFoundException::new);
    user.updateProfile(
        displayName == null ? user.displayName() : new DisplayName(displayName),
        aboutMe == null ? user.aboutMe() : new AboutMe(aboutMe),
        clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<DomainEvent> events = user.pullEvents();
    User saved = userRepository.save(user);
    events.forEach(eventPublisher::publish);
    return saved;
  }
}

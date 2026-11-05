package com.nexus.identity.application.auth;

import com.nexus.identity.application.port.DomainEventPublisher;
import com.nexus.identity.application.port.PasswordHasher;
import com.nexus.identity.domain.shared.DomainEvent;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.RawPassword;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đăng ký: tạo user, lưu rồi phát UserRegistered, trùng khóa dựa vào unique index
@Service
public class RegisterUserUseCase {

  private final UserRepository userRepository;
  private final PasswordHasher passwordHasher;
  private final DomainEventPublisher eventPublisher;
  private final Clock clock;

  // Nhận các cổng qua constructor
  public RegisterUserUseCase(
      UserRepository userRepository,
      PasswordHasher passwordHasher,
      DomainEventPublisher eventPublisher,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordHasher = passwordHasher;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // Đăng ký user mới và ghi event cùng transaction, trả user đã lưu
  @Transactional
  public User execute(RegisterCommand command) {
    Email email = new Email(command.email());
    Username username = new Username(command.username());
    DisplayName displayName =
        new DisplayName(
            command.displayName() == null || command.displayName().isBlank()
                ? username.value()
                : command.displayName());
    var hash = passwordHasher.hash(new RawPassword(command.password()));
    User user =
        User.register(
            UserId.newId(),
            email,
            username,
            displayName,
            hash,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    List<DomainEvent> events = user.pullEvents();
    User saved = userRepository.save(user);
    events.forEach(eventPublisher::publish);
    return saved;
  }
}

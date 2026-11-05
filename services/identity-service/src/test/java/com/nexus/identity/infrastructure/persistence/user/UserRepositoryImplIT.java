package com.nexus.identity.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.domain.user.AboutMe;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.EmailAlreadyTakenException;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import com.nexus.identity.domain.user.UsernameAlreadyTakenException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

// Kiểm tra UserRepositoryImpl với Postgres thật: lưu, tìm, trùng khóa duy nhất, khóa lạc quan
@SpringBootTest
class UserRepositoryImplIT extends IdentityIntegrationTest {

  @Autowired private UserRepository repository;

  // Kịch bản: lưu user mới rồi tìm lại theo id, email và username
  // Input: user register với email và username ngẫu nhiên
  // Output: bản trả về có version 0 và cả ba lần tìm đều ra cùng dữ liệu
  @Test
  void savesAndFindsByEmailAndUsername() {
    User saved = repository.save(newUser(tag(), tag()));

    assertThat(saved.version()).isEqualTo(0L);
    assertThat(repository.findById(saved.id())).get().usingRecursiveComparison().isEqualTo(saved);
    assertThat(repository.findByEmail(saved.email()))
        .get()
        .usingRecursiveComparison()
        .isEqualTo(saved);
    assertThat(repository.findByUsername(saved.username()))
        .get()
        .usingRecursiveComparison()
        .isEqualTo(saved);
  }

  // Kịch bản: lưu user thứ hai có email khác hoa thường nhưng cùng giá trị sau chuẩn hóa
  // Input: Email("a@x.com") rồi Email("A@X.com") với username khác nhau
  // Output: lần lưu thứ hai ném EmailAlreadyTakenException
  @Test
  void duplicateEmailDifferentCaseThrowsEmailTaken() {
    String local = tag();
    repository.save(newUser(local + "@x.com", tag()));

    assertThatThrownBy(() -> repository.save(newUser(local.toUpperCase() + "@X.com", tag())))
        .isInstanceOf(EmailAlreadyTakenException.class);
  }

  // Kịch bản: lưu user thứ hai trùng username
  // Input: hai user cùng username, email khác nhau
  // Output: lần lưu thứ hai ném UsernameAlreadyTakenException
  @Test
  void duplicateUsernameThrowsUsernameTaken() {
    String username = tag();
    repository.save(newUser(tag(), username));

    assertThatThrownBy(() -> repository.save(newUser(tag(), username)))
        .isInstanceOf(UsernameAlreadyTakenException.class);
  }

  // Kịch bản: hai bản cùng version cùng sửa, bản lưu sau đã cũ
  // Input: tải hai bản của một user, updateProfile cả hai, lưu bản 1 rồi bản 2
  // Output: lưu bản 2 ném ObjectOptimisticLockingFailureException
  @Test
  void staleVersionSaveFailsWithOptimisticLock() {
    UserId id = repository.save(newUser(tag(), tag())).id();
    User first = repository.findById(id).orElseThrow();
    User second = repository.findById(id).orElseThrow();
    first.updateProfile(new DisplayName("First"), new AboutMe("one"), Instant.now());
    second.updateProfile(new DisplayName("Second"), new AboutMe("two"), Instant.now());

    User savedFirst = repository.save(first);

    assertThat(savedFirst.version()).isEqualTo(1L);
    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  // Kịch bản: tìm theo danh sách id gồm cả id không tồn tại
  // Input: id của hai user đã lưu và một id lạ
  // Output: chỉ trả hai user đã lưu
  @Test
  void findAllByIdsReturnsOnlyExisting() {
    User a = repository.save(newUser(tag(), tag()));
    User b = repository.save(newUser(tag(), tag()));

    List<User> found = repository.findAllByIds(List.of(a.id(), b.id(), UserId.newId()));

    assertThat(found).extracting(User::id).containsExactlyInAnyOrder(a.id(), b.id());
  }

  // Sinh chuỗi hex chữ thường ngẫu nhiên đủ ngắn làm email local-part hoặc username
  private static String tag() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Dựng user mới chưa lưu từ email (thêm @x.com nếu thiếu) và username
  private static User newUser(String email, String username) {
    String address = email.contains("@") ? email : email + "@x.com";
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return User.register(
        UserId.newId(),
        new Email(address),
        new Username(username),
        new DisplayName("Name"),
        new PasswordHash("$2a$12$hash"),
        now);
  }
}

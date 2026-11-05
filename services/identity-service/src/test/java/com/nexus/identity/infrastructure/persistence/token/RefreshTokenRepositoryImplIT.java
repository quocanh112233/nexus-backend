package com.nexus.identity.infrastructure.persistence.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.UserRepository;
import com.nexus.identity.domain.user.Username;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra RefreshTokenRepositoryImpl với Postgres thật: lưu, tìm, thu hồi theo token và family
@SpringBootTest
class RefreshTokenRepositoryImplIT extends IdentityIntegrationTest {

  @Autowired private RefreshTokenRepository repository;
  @Autowired private UserRepository users;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: lưu token mới rồi tìm lại theo hash
  // Input: token phát cho một user đã lưu, có userAgent và ip
  // Output: findByHash trả token bằng token đã lưu từng field
  @Test
  void savesAndFindsByHash() {
    RefreshToken token = newToken(savedUser(), UUID.randomUUID());

    repository.save(token);

    assertThat(repository.findByHash(token.tokenHash()))
        .get()
        .usingRecursiveComparison()
        .isEqualTo(token);
    assertThat(repository.findByHash(TokenHash.ofRaw(UUID.randomUUID().toString()))).isEmpty();
  }

  // Kịch bản: thu hồi cùng một token hai lần
  // Input: revokeIfActive hai lần liên tiếp với token đang hoạt động
  // Output: lần 1 true và token mang revokedAt, replacedById; lần 2 false
  @Test
  void revokeIfActiveTrueOnceThenFalse() {
    RefreshToken token = repository.save(newToken(savedUser(), UUID.randomUUID()));
    UUID replacement = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    assertThat(revoke(token.id(), replacement, now)).isTrue();
    assertThat(revoke(token.id(), replacement, now)).isFalse();

    RefreshToken reloaded = repository.findByHash(token.tokenHash()).orElseThrow();
    assertThat(reloaded.revokedAt()).isEqualTo(now);
    assertThat(reloaded.replacedById()).isEqualTo(replacement);
  }

  // Kịch bản: thu hồi cả family
  // Input: hai token cùng family (một đã thu hồi trước) và một token family khác
  // Output: trả 1, token family khác vẫn chưa thu hồi và revokedAt cũ không bị ghi đè
  @Test
  void revokeFamilyRevokesAllActiveTokensOfFamilyOnly() {
    UserId user = savedUser();
    UUID family = UUID.randomUUID();
    RefreshToken revoked = repository.save(newToken(user, family));
    RefreshToken active = repository.save(newToken(user, family));
    RefreshToken other = repository.save(newToken(user, UUID.randomUUID()));
    Instant first = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant second = first.plusSeconds(60);
    tx.executeWithoutResult(s -> repository.revokeIfActive(revoked.id(), active.id(), first));

    Integer count = tx.execute(s -> repository.revokeFamily(family, second));

    assertThat(count).isEqualTo(1);
    assertThat(repository.findByHash(active.tokenHash()).orElseThrow().revokedAt())
        .isEqualTo(second);
    assertThat(repository.findByHash(revoked.tokenHash()).orElseThrow().revokedAt())
        .isEqualTo(first);
    assertThat(repository.findByHash(other.tokenHash()).orElseThrow().isRevoked()).isFalse();
  }

  // Kịch bản: hai luồng cùng thu hồi một token, mỗi luồng một transaction riêng
  // Input: hai thread chạy revokeIfActive đồng thời trên cùng token
  // Output: đúng một luồng nhận true, luồng còn lại nhận false
  @Test
  void twoThreadsRevokeSameTokenExactlyOneWins() throws Exception {
    RefreshToken token = repository.save(newToken(savedUser(), UUID.randomUUID()));
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> task =
        () -> {
          start.await();
          return revoke(token.id(), UUID.randomUUID(), now);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> a = pool.submit(task);
      Future<Boolean> b = pool.submit(task);
      start.countDown();

      assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
  }

  // Gọi revokeIfActive trong một transaction riêng
  private boolean revoke(UUID id, UUID replacedById, Instant now) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.revokeIfActive(id, replacedById, now)));
  }

  // Lưu một user ngẫu nhiên để thỏa khóa ngoại và trả id
  private UserId savedUser() {
    String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    return users
        .save(
            User.register(
                UserId.newId(),
                new Email(tag + "@x.com"),
                new Username(tag),
                new DisplayName("Name"),
                new PasswordHash("$2a$12$hash"),
                Instant.now().truncatedTo(ChronoUnit.MICROS)))
        .id();
  }

  // Dựng token mới chưa lưu thuộc user và family cho trước
  private static RefreshToken newToken(UserId user, UUID family) {
    return RefreshToken.issue(
        UUID.randomUUID(),
        user,
        family,
        TokenHash.ofRaw(UUID.randomUUID().toString()),
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        Duration.ofDays(30),
        "agent",
        "10.0.0.1");
  }
}

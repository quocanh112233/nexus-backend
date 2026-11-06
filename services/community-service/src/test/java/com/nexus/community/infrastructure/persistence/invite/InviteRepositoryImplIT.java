package com.nexus.community.infrastructure.persistence.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra InviteRepositoryImpl với Postgres thật, nhất là câu UPDATE tryConsume
@SpringBootTest
class InviteRepositoryImplIT extends CommunityIntegrationTest {

  @Autowired private InviteRepository repository;
  @Autowired private ServerRepository servers;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: tryConsume chỉ tăng khi lời mời còn dùng được
  // Input: lời mời hết hạn, đã thu hồi, maxUses=1 dùng hai lần, maxUses=0 dùng ba lần
  // Output: false/false; true rồi false; true ba lần; uses trong DB khớp số lần true
  @Test
  void tryConsumeIncrementsOnlyWhenUsable() {
    Server server = savedServer();
    Instant now = now();
    Invite expired = invite(server, 0, now.minus(Duration.ofMinutes(1)), null);
    Invite revoked = invite(server, 0, now.plus(Duration.ofDays(1)), now);
    Invite single = invite(server, 1, now.plus(Duration.ofDays(1)), null);
    Invite unlimited = invite(server, 0, now.plus(Duration.ofDays(1)), null);

    assertThat(consume(expired.code(), now)).isFalse();
    assertThat(consume(revoked.code(), now)).isFalse();
    assertThat(consume(single.code(), now)).isTrue();
    assertThat(consume(single.code(), now)).isFalse();
    for (int i = 0; i < 3; i++) {
      assertThat(consume(unlimited.code(), now)).isTrue();
    }

    assertThat(uses(expired)).isZero();
    assertThat(uses(revoked)).isZero();
    assertThat(uses(single)).isEqualTo(1);
    assertThat(uses(unlimited)).isEqualTo(3);
  }

  // Kịch bản: 20 luồng cùng tryConsume một lời mời 5 lượt, mỗi luồng một transaction
  // Input: maxUses=5, 20 lời gọi đồng thời sau cổng CountDownLatch
  // Output: đúng 5 true, 15 false, uses = 5
  @Test
  void tryConsumeIsAtomicUnderConcurrency() throws Exception {
    Server server = savedServer();
    Invite invite = invite(server, 5, now().plus(Duration.ofDays(1)), null);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 20; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return consume(invite.code(), now());
                }));
      }
      start.countDown();
      int ok = 0;
      for (Future<Boolean> f : results) {
        ok += f.get() ? 1 : 0;
      }
      assertThat(ok).isEqualTo(5);
    } finally {
      pool.shutdownNow();
    }
    assertThat(uses(invite)).isEqualTo(5);
  }

  // Kịch bản: save rồi đọc lại và liệt kê theo created_at giảm dần, revoke chỉ ghi revoked_at
  // Input: hai lời mời cách nhau 1 giây; đọc lời mời cũ (uses 0), tiêu một lượt, rồi thu hồi bản cũ
  // Output: findAllByServer mới trước; lời mời cũ có revokedAt và uses vẫn là 1
  @Test
  void saveFindListAndRevokeKeepsUses() {
    Server server = savedServer();
    Instant now = now();
    Invite older = invite(server, 0, now.plus(Duration.ofDays(1)), null);
    Invite newer =
        Invite.create(
            randomCode(),
            server.id(),
            server.ownerId(),
            0,
            now.plus(Duration.ofDays(1)),
            now.plusSeconds(1));
    repository.add(newer);
    Invite stale = repository.findByCode(older.code()).orElseThrow();
    consume(older.code(), now);

    stale.revoke(now);
    repository.save(stale);

    assertThat(repository.findAllByServer(server.id()))
        .extracting(Invite::code)
        .containsExactly(newer.code(), older.code());
    Invite reloaded = repository.findByCode(older.code()).orElseThrow();
    assertThat(reloaded.revokedAt()).isEqualTo(now);
    assertThat(reloaded.uses()).isEqualTo(1);
  }

  // Kịch bản: add lời mời mới trùng mã với lời mời đã dùng và đã thu hồi không được đè hàng cũ
  // Input: lời mời maxUses=5 dùng 1 lượt rồi thu hồi; add Invite.create cùng mã với maxUses=9
  // Output: ném DataIntegrityViolationException; hàng cũ giữ revokedAt, uses=1 và maxUses=5
  @Test
  void addWithExistingCodeFailsAndKeepsRow() {
    Server server = savedServer();
    Instant now = now();
    Invite original = invite(server, 5, now.plus(Duration.ofDays(1)), null);
    consume(original.code(), now);
    Invite stale = repository.findByCode(original.code()).orElseThrow();
    stale.revoke(now);
    repository.save(stale);
    Invite clash =
        Invite.create(
            original.code(),
            server.id(),
            server.ownerId(),
            9,
            now.plus(Duration.ofDays(2)),
            now.plusSeconds(5));

    assertThatThrownBy(() -> repository.add(clash))
        .isInstanceOf(DataIntegrityViolationException.class);

    Invite reloaded = repository.findByCode(original.code()).orElseThrow();
    assertThat(reloaded.revokedAt()).isEqualTo(now);
    assertThat(reloaded.uses()).isEqualTo(1);
    assertThat(reloaded.maxUses()).isEqualTo(5);
  }

  // Thêm lời mời qua rehydrate để cho phép hạn trong quá khứ và revokedAt tùy chọn
  private Invite invite(Server server, int maxUses, Instant expiresAt, Instant revokedAt) {
    Invite invite =
        Invite.rehydrate(
            randomCode(), server.id(), server.ownerId(), maxUses, 0, expiresAt, revokedAt, now());
    repository.add(invite);
    return invite;
  }

  // Gọi tryConsume trong một transaction riêng
  private boolean consume(InviteCode code, Instant now) {
    return Boolean.TRUE.equals(tx.execute(s -> repository.tryConsume(code, now)));
  }

  // Đọc uses hiện tại trong DB
  private int uses(Invite invite) {
    return repository.findByCode(invite.code()).orElseThrow().uses();
  }

  // Sinh mã mời ngẫu nhiên 8 ký tự
  private static InviteCode randomCode() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
    }
    return new InviteCode(sb.toString());
  }

  // Lưu một server mới làm chỗ chứa cho lời mời
  private Server savedServer() {
    Server server = Server.create(new ServerName("s"), UserId.newId(), now());
    servers.save(server);
    return server;
  }

  // Thời điểm hiện tại cắt về micro để khớp timestamptz
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}

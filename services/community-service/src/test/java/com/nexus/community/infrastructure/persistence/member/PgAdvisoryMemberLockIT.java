package com.nexus.community.infrastructure.persistence.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.community.application.port.MemberLock;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra PgAdvisoryMemberLock với Postgres thật: cùng cặp thì tuần tự, khác cặp thì song song
@SpringBootTest
class PgAdvisoryMemberLockIT extends CommunityIntegrationTest {

  private static final long WAIT_SECONDS = 10;

  @Autowired private MemberLock lock;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: hai transaction cùng khóa (server,user), transaction hai chỉ qua sau khi một commit
  // Input: T1 lock rồi giữ đến khi nhả chốt; T2 lock cùng cặp khi T1 còn giữ
  // Output: T2 chưa lấy được khóa khi T1 còn giữ, lấy được ngay sau khi T1 commit
  @Test
  void sameKeySerializesTransactions() throws Exception {
    ServerId server = ServerId.newId();
    UserId user = UserId.newId();
    CountDownLatch firstHolds = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondAcquired = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> holdUntil(server, user, firstHolds, releaseFirst));
      assertThat(firstHolds.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Future<?> second =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        secondStarted.countDown();
                        lock.lock(server, user);
                        secondAcquired.countDown();
                      }));
      assertThat(secondStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

      assertThat(secondAcquired.await(1, TimeUnit.SECONDS)).isFalse();
      releaseFirst.countDown();
      first.get(WAIT_SECONDS, TimeUnit.SECONDS);

      assertThat(secondAcquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      second.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
    }
  }

  // Kịch bản: hai transaction khóa hai cặp khác nhau không chặn nhau
  // Input: T1 giữ khóa (server,userA); T2 lock (server,userB) và (otherServer,userA)
  // Output: T2 lấy được cả hai khóa trong hạn dù T1 vẫn đang giữ
  @Test
  void differentKeyDoesNotBlock() throws Exception {
    ServerId server = ServerId.newId();
    UserId userA = UserId.newId();
    CountDownLatch firstHolds = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> holdUntil(server, userA, firstHolds, releaseFirst));
      assertThat(firstHolds.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

      Future<?> second =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        lock.lock(server, UserId.newId());
                        lock.lock(ServerId.newId(), userA);
                      }));

      second.get(WAIT_SECONDS, TimeUnit.SECONDS);
      releaseFirst.countDown();
      first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
    }
  }

  // Kịch bản: lấy khóa ngoài transaction bị từ chối ngay vì khóa xact sẽ nhả tức thì
  // Input: gọi lock(server, user) không bọc transaction
  // Output: ném IllegalStateException "MemberLock requires an active transaction"
  @Test
  void lockOutsideTransactionFailsFast() {
    assertThatThrownBy(() -> lock.lock(ServerId.newId(), UserId.newId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MemberLock requires an active transaction");
  }

  // Lấy khóa trong một transaction, báo đã giữ rồi giữ đến khi chốt release được nhả
  private void holdUntil(
      ServerId server, UserId user, CountDownLatch holding, CountDownLatch release) {
    tx.executeWithoutResult(
        s -> {
          lock.lock(server, user);
          holding.countDown();
          try {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }
}

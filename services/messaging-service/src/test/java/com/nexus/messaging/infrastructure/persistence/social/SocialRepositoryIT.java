package com.nexus.messaging.infrastructure.persistence.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestExistsException;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import com.nexus.messaging.domain.social.FriendRequestStatus;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra repository bạn bè và DM trên Postgres thật, nhất là chống đua bằng ràng buộc DB
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class SocialRepositoryIT extends MessagingIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private static final Instant LATER = Instant.parse("2026-11-09T11:00:00Z");

  @Autowired private FriendRequestRepository requests;
  @Autowired private FriendshipRepository friendships;
  @Autowired private DmChannelRepository dms;
  @Autowired private TransactionTemplate tx;

  // Kịch bản: mời ngược chiều khi đã có PENDING, rồi mời lại sau khi bản đầu kết thúc
  // Input: save A→B, save B→A trong transaction của caller, accept A→B, save A→B mới
  // Output: B→A ném FriendRequestExistsException; accept trả version 1; lời mời mới lưu được
  @Test
  void pendingIsUniquePerPairUntilTerminal() {
    UserId a = user();
    UserId b = user();
    FriendRequest first = requests.save(send(a, b));

    assertThatThrownBy(() -> tx.executeWithoutResult(s -> requests.save(send(b, a))))
        .isInstanceOf(FriendRequestExistsException.class);
    assertThat(requests.findPending(UserPair.of(b, a))).map(FriendRequest::id).contains(first.id());
    assertThat(requests.pendingIncoming(b))
        .extracting(FriendRequest::id)
        .containsExactly(first.id());
    assertThat(requests.pendingOutgoing(a))
        .extracting(FriendRequest::id)
        .containsExactly(first.id());

    FriendRequest loaded = requests.findById(first.id()).orElseThrow();
    loaded.accept(b, LATER);
    FriendRequest accepted = requests.save(loaded);

    assertThat(accepted.version()).isEqualTo(1);
    assertThat(accepted.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
    assertThat(accepted.respondedAt()).isEqualTo(LATER);
    assertThat(requests.findPending(UserPair.of(a, b))).isEmpty();
    FriendRequest again = requests.save(send(a, b));
    assertThat(requests.findPending(UserPair.of(a, b))).map(FriendRequest::id).contains(again.id());
  }

  // Kịch bản: A→B và B→A gửi cùng lúc
  // Input: hai luồng save PENDING ngược chiều trên cùng cặp
  // Output: đúng một bên thành công, bên còn lại FriendRequestExistsException
  @Test
  void concurrentOppositeRequestsKeepOnePending() throws Exception {
    UserId a = user();
    UserId b = user();

    List<Object> outcomes = race(() -> requests.save(send(a, b)), () -> requests.save(send(b, a)));

    assertThat(outcomes).filteredOn(o -> o instanceof FriendRequest).hasSize(1);
    assertThat(outcomes).filteredOn(o -> o instanceof FriendRequestExistsException).hasSize(1);
  }

  // Kịch bản: hai luồng cùng mở DM cho một cặp
  // Input: insertIfAbsent hai DmChannel khác id cùng pair đồng thời
  // Output: đúng một true; findByPair, findById, listOf thấy kênh thắng
  @Test
  void concurrentDmInsertKeepsOne() throws Exception {
    UserPair pair = UserPair.of(user(), user());
    DmChannel c1 = DmChannel.open(UUID.randomUUID(), pair, NOW);
    DmChannel c2 = DmChannel.open(UUID.randomUUID(), pair, NOW);

    List<Object> outcomes = race(() -> dms.insertIfAbsent(c1), () -> dms.insertIfAbsent(c2));

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    UUID winner = outcomes.get(0).equals(true) ? c1.id() : c2.id();
    DmChannel found = dms.findByPair(pair).orElseThrow();
    assertThat(found.id()).isEqualTo(winner);
    assertThat(found.pair()).isEqualTo(pair);
    assertThat(found.createdAt()).isEqualTo(NOW);
    assertThat(dms.findById(new ChannelId(winner))).isPresent();
    assertThat(dms.listOf(pair.high())).extracting(DmChannel::id).containsExactly(winner);
    assertThat(dms.listOf(pair.low())).extracting(DmChannel::id).containsExactly(winner);
  }

  // Kịch bản: liệt kê DM của một user khi hai kênh có cùng createdAt
  // Input: user có hai DM createdAt giống nhau, id nhỏ chèn trước id lớn
  // Output: listOf trả id lớn trước id nhỏ (created_at DESC, id DESC) cho cả hai lần gọi
  @Test
  void listOfBreaksCreatedAtTiesByIdDesc() {
    UserId me = user();
    UUID small = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID large = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    tx.executeWithoutResult(
        s -> {
          dms.insertIfAbsent(DmChannel.open(small, UserPair.of(me, user()), NOW));
          dms.insertIfAbsent(DmChannel.open(large, UserPair.of(me, user()), NOW));
        });

    assertThat(dms.listOf(me)).extracting(DmChannel::id).containsExactly(large, small);
    assertThat(dms.listOf(me)).extracting(DmChannel::id).containsExactly(large, small);
  }

  // Kịch bản: hai luồng cùng kết bạn cho một cặp rồi hủy kết bạn
  // Input: insertIfAbsent cùng pair đồng thời, exists, delete hai lần
  // Output: đúng một true; exists true; delete true rồi false; exists false
  @Test
  void concurrentFriendshipInsertKeepsOne() throws Exception {
    UserPair pair = UserPair.of(user(), user());

    List<Object> outcomes =
        race(
            () -> friendships.insertIfAbsent(pair, NOW),
            () -> friendships.insertIfAbsent(pair, NOW));

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(friendships.exists(pair)).isTrue();
    assertThat(tx.<Boolean>execute(s -> friendships.delete(pair))).isTrue();
    assertThat(tx.<Boolean>execute(s -> friendships.delete(pair))).isFalse();
    assertThat(friendships.exists(pair)).isFalse();
  }

  // Kịch bản: user là low trong một cặp và high trong cặp khác, uuid khác dấu theo Java
  // Input: u=80..., bạn 00...01 (u là high) và ff... (u là low)
  // Output: friendIdsOf(u) trả đủ cả hai bạn
  @Test
  void friendIdsOfReturnsBothSides() {
    String suffix = UUID.randomUUID().toString().substring(8);
    UserId u = new UserId(UUID.fromString("80000000" + suffix));
    UserId lower = new UserId(UUID.fromString("00000000" + suffix));
    UserId higher = new UserId(UUID.fromString("ffffffff" + suffix));
    tx.executeWithoutResult(s -> friendships.insertIfAbsent(UserPair.of(u, lower), NOW));
    tx.executeWithoutResult(s -> friendships.insertIfAbsent(UserPair.of(higher, u), NOW));

    assertThat(friendships.friendIdsOf(u)).containsExactlyInAnyOrder(lower, higher);
  }

  // Kịch bản: hai luồng cùng phản hồi một lời mời đọc ở cùng version
  // Input: hai bản đọc version 0, một accept một decline, save đồng thời
  // Output: đúng một bên thành công, bên kia OptimisticLockingFailureException
  @Test
  void concurrentSaveSameVersionFailsOptimistically() throws Exception {
    UserId a = user();
    UserId b = user();
    FriendRequest saved = requests.save(send(a, b));
    FriendRequest copy1 = requests.findById(saved.id()).orElseThrow();
    FriendRequest copy2 = requests.findById(saved.id()).orElseThrow();
    copy1.accept(b, LATER);
    copy2.decline(b, LATER);

    List<Object> outcomes = race(() -> requests.save(copy1), () -> requests.save(copy2));

    assertThat(outcomes).filteredOn(o -> o instanceof FriendRequest).hasSize(1);
    assertThat(outcomes).filteredOn(o -> o instanceof OptimisticLockingFailureException).hasSize(1);
    assertThat(requests.findById(saved.id()).orElseThrow().version()).isEqualTo(1);
  }

  // Chạy hai tác vụ cùng lúc, mỗi tác vụ một transaction, trả kết quả hoặc ngoại lệ từng tác vụ
  private List<Object> race(Supplier<?> first, Supplier<?> second) throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (Supplier<?> task : List.of(first, second)) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return tx.execute(s -> task.get());
                }));
      }
      start.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<?> future : futures) {
        try {
          outcomes.add(future.get());
        } catch (ExecutionException e) {
          outcomes.add(e.getCause());
        }
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  // Tạo lời mời đang chờ mới
  private FriendRequest send(UserId from, UserId to) {
    return FriendRequest.send(FriendRequestId.newId(), from, to, NOW);
  }

  // Tạo người dùng ngẫu nhiên
  private UserId user() {
    return new UserId(UUID.randomUUID());
  }
}

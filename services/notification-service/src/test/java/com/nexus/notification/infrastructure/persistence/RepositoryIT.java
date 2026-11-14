package com.nexus.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.notification.domain.readstate.ChannelActivityRepository;
import com.nexus.notification.domain.readstate.ChannelReadState;
import com.nexus.notification.domain.readstate.ChannelScope;
import com.nexus.notification.domain.readstate.MembershipRepository;
import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.readstate.ReadStateSnapshot;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra bốn repository native với Postgres thật: thứ tự uuid, upsert nguyên tử và đua khóa
@SpringBootTest
class RepositoryIT extends NotificationIntegrationTest {

  @Autowired private ChannelActivityRepository activity;
  @Autowired private MembershipRepository members;
  @Autowired private ReadStateRepository readStates;
  @Autowired private MentionRepository mentions;
  @Autowired private TransactionTemplate tx;
  @Autowired private JdbcTemplate jdbc;

  // Kịch bản: tin đến đảo thứ tự, last_message_id chỉ tiến và server_id giữ giá trị đầu
  // Input: kênh 1 lớn rồi nhỏ (S); kênh 2 nhỏ (S) rồi lớn (null); kênh 3 nhỏ (null) rồi lớn (S)
  // Output: cả ba kênh last_message_id = id lớn, last_message_at theo id lớn, server_id = S
  @Test
  void recordMessageKeepsGreatestId() {
    ServerId server = server();
    MessageId small = message();
    MessageId big = message();
    Instant at = now();
    ChannelId first = channel();
    ChannelId second = channel();
    run(() -> activity.recordMessage(first, server, big, at));
    run(() -> activity.recordMessage(first, server, small, at.minusSeconds(5)));
    run(() -> activity.recordMessage(second, server, small, at.minusSeconds(5)));
    run(() -> activity.recordMessage(second, null, big, at));
    ChannelId third = channel();
    run(() -> activity.recordMessage(third, null, small, at.minusSeconds(5)));
    run(() -> activity.recordMessage(third, server, big, at));

    for (ChannelId c : List.of(first, second, third)) {
      assertThat(lastMessage(c)).isEqualTo(big.value());
      assertThat(serverOf(c)).isEqualTo(server.value());
      assertThat(lastMessageAt(c)).isEqualTo(at);
    }
  }

  // Kịch bản: so sánh uuid theo byte của Postgres, không theo thứ tự có dấu của Java
  // Input: lo có byte đầu 0x01, hi có byte đầu 0x80 (Java cho hi < lo); recordMessage, advance
  // Output: last_message_id = hi; advance(hi) rồi advance(lo) giữ hi; mention lo bị đếm 0
  @Test
  void uuidOrderingIsBytewiseNotJavaSigned() {
    UUID lo = new UUID(0x0100_0000_0000_7000L, 0x8000_0000_0000_0001L);
    UUID hi = new UUID(0x8000_0000_0000_7000L, 0x8000_0000_0000_0001L);
    assertThat(hi.compareTo(lo)).isNegative();
    ChannelId c = channel();
    UserId user = user();
    run(() -> activity.recordMessage(c, server(), new MessageId(hi), now()));
    run(() -> activity.recordMessage(c, null, new MessageId(lo), now()));

    ReadStateSnapshot afterHi = call(() -> readStates.advance(user, c, new MessageId(hi)));
    ReadStateSnapshot afterLo = call(() -> readStates.advance(user, c, new MessageId(lo)));
    int count = call(() -> readStates.addMentionCount(user, c, new MessageId(lo)));

    assertThat(lastMessage(c)).isEqualTo(hi);
    assertThat(afterHi.lastRead().value()).isEqualTo(hi);
    assertThat(afterLo.lastRead().value()).isEqualTo(hi);
    assertThat(count).isZero();
  }

  // Kịch bản: ChannelDeleted đến trước ChannelCreated
  // Input: tombstoneChannel rồi upsertChannel cùng kênh
  // Output: deleted_at vẫn khác null, findLiveChannel rỗng
  @Test
  void tombstoneBeforeCreateSticks() {
    ChannelId c = channel();
    ServerId s = server();
    run(() -> activity.tombstoneChannel(c, s, now()));
    run(() -> activity.upsertChannel(c, s));

    assertThat(
            jdbc.queryForObject(
                "select deleted_at is not null from notification.channel_activity"
                    + " where channel_id = ?",
                Boolean.class,
                c.value()))
        .isTrue();
    assertThat(activity.findLiveChannel(c)).isEmpty();
  }

  // Kịch bản: kênh sống tìm thấy kèm server, kênh DM có serverId rỗng, server bị xóa thì mất
  // Input: upsertChannel kênh server và kênh DM; tombstoneServer server khác có một kênh
  // Output: findLiveChannel trả đúng scope; kênh của server bị xóa rỗng
  @Test
  void findLiveChannelReturnsScopeAndHonorsServerTombstone() {
    ChannelId c = channel();
    ChannelId dm = channel();
    ChannelId gone = channel();
    ServerId s = server();
    ServerId deleted = server();
    run(() -> activity.upsertChannel(c, s));
    run(() -> activity.upsertChannel(dm, null));
    run(() -> activity.upsertChannel(gone, deleted));
    run(() -> activity.tombstoneServer(deleted, now()));

    assertThat(activity.findLiveChannel(c)).contains(new ChannelScope(c, s));
    assertThat(activity.findLiveChannel(dm)).contains(new ChannelScope(dm, null));
    assertThat(activity.findLiveChannel(gone)).isEmpty();
  }

  // Kịch bản: PUT đọc chỉ tiến và tính lại số mention sau mốc
  // Input: 3 mention a<b<c; advance(b), advance(a), advance(c)
  // Output: (b,1), (b,1), (c,0)
  @Test
  void advanceNeverMovesBackAndRecountsMentions() {
    UserId user = user();
    ChannelId c = channel();
    MessageId a = message();
    MessageId b = message();
    MessageId last = message();
    for (MessageId m : List.of(a, b, last)) {
      run(() -> mentions.insertIfAbsent(m, user, c, null, user(), now()));
    }

    ReadStateSnapshot first = call(() -> readStates.advance(user, c, b));
    ReadStateSnapshot back = call(() -> readStates.advance(user, c, a));
    ReadStateSnapshot end = call(() -> readStates.advance(user, c, last));

    assertThat(first).isEqualTo(new ReadStateSnapshot(b, 1));
    assertThat(back).isEqualTo(new ReadStateSnapshot(b, 1));
    assertThat(end).isEqualTo(new ReadStateSnapshot(last, 0));
  }

  // Kịch bản: mention tin đã nằm dưới mốc đọc không cộng, chưa có mốc thì cộng
  // Input: lastRead=c rồi addMentionCount(a); user khác chưa có dòng read_states addMentionCount(a)
  // Output: user 1 giữ 0; user 2 được 1
  @Test
  void addMentionCountSkipsAlreadyReadMessage() {
    UserId reader = user();
    UserId fresh = user();
    ChannelId c = channel();
    MessageId a = message();
    MessageId last = message();
    call(() -> readStates.advance(reader, c, last));

    int skipped = call(() -> readStates.addMentionCount(reader, c, a));
    int counted = call(() -> readStates.addMentionCount(fresh, c, a));

    assertThat(skipped).isZero();
    assertThat(counted).isEqualTo(1);
  }

  // Kịch bản: cùng mention giao hai lần
  // Input: insertIfAbsent hai lần cùng (message, user)
  // Output: true rồi false
  @Test
  void mentionInsertIsIdempotent() {
    MessageId m = message();
    UserId u = user();
    ChannelId c = channel();

    boolean first = call(() -> mentions.insertIfAbsent(m, u, c, server(), user(), now()));
    boolean second = call(() -> mentions.insertIfAbsent(m, u, c, server(), user(), now()));

    assertThat(first).isTrue();
    assertThat(second).isFalse();
  }

  // Kịch bản: user vào server, chỉ kênh sống đã có tin được đặt mốc
  // Input: 2 kênh có tin, 1 kênh có tin nhưng tombstone, 1 kênh chưa có tin; baseline hai lần
  // Output: đúng 2 dòng read_states, last_read = last_message_id, mention_count = 0
  @Test
  void baselineForJoinCopiesLastMessageForLiveChannelsOnly() {
    ServerId s = server();
    UserId u = user();
    ChannelId c1 = channel();
    ChannelId c2 = channel();
    ChannelId deleted = channel();
    ChannelId empty = channel();
    MessageId m1 = message();
    MessageId m2 = message();
    run(() -> activity.recordMessage(c1, s, m1, now()));
    run(() -> activity.recordMessage(c2, s, m2, now()));
    run(() -> activity.recordMessage(deleted, s, message(), now()));
    run(() -> activity.tombstoneChannel(deleted, s, now()));
    run(() -> activity.upsertChannel(empty, s));

    run(() -> readStates.baselineForJoin(s, u));
    run(() -> readStates.baselineForJoin(s, u));

    List<UUID> lastReads =
        jdbc.queryForList(
            "select last_read_message_id from notification.read_states"
                + " where user_id = ? and mention_count = 0",
            UUID.class,
            u.value());
    assertThat(lastReads).containsExactlyInAnyOrder(m1.value(), m2.value());
    assertThat(readStateCount(u)).isEqualTo(2);
  }

  // Kịch bản: GET read state chỉ thấy kênh truy cập được, sống và đã có tin, unread do SQL tính
  // Input: member S1 (kênh chưa đọc, kênh đã đọc hết, kênh tombstone, kênh chưa tin), kênh S2, DM
  // Output: đúng 3 dòng: unread=true, unread=false, DM serverId null unread=true
  @Test
  void listForReturnsOnlyAccessibleLiveChannelsWithUnreadFlag() {
    UserId u = user();
    ServerId s1 = server();
    ServerId s2 = server();
    ChannelId unread = channel();
    ChannelId read = channel();
    ChannelId deleted = channel();
    ChannelId empty = channel();
    ChannelId foreign = channel();
    ChannelId dm = channel();
    MessageId readMessage = message();
    run(() -> members.addServerMember(s1, u, now()));
    run(() -> members.addDmParticipants(dm, u, user()));
    run(() -> activity.recordMessage(unread, s1, message(), now()));
    run(() -> activity.recordMessage(read, s1, readMessage, now()));
    run(() -> activity.recordMessage(deleted, s1, message(), now()));
    run(() -> activity.tombstoneChannel(deleted, s1, now()));
    run(() -> activity.upsertChannel(empty, s1));
    run(() -> activity.recordMessage(foreign, s2, message(), now()));
    run(() -> activity.recordMessage(dm, null, message(), now()));
    call(() -> readStates.advance(u, read, readMessage));

    List<ChannelReadState> rows = readStates.listFor(u);

    assertThat(rows)
        .extracting(
            ChannelReadState::channelId, ChannelReadState::serverId, ChannelReadState::unread)
        .containsExactlyInAnyOrder(
            tuple(unread, s1, true), tuple(read, s1, false), tuple(dm, null, true));
    assertThat(rows)
        .filteredOn(r -> r.channelId().equals(read))
        .singleElement()
        .satisfies(r -> assertThat(r.lastReadMessageId()).isEqualTo(readMessage));
  }

  // Kịch bản: quyền truy cập theo thành viên server hoặc participant DM
  // Input: member S, participant DM, người ngoài; xóa member rồi xóa cả server
  // Output: true cho member và participant, false cho người ngoài và sau khi xóa
  @Test
  void canAccessCoversServerMemberAndDmParticipant() {
    ServerId s = server();
    UserId member = user();
    UserId other = user();
    UserId stranger = user();
    ChannelScope serverChannel = new ChannelScope(channel(), s);
    ChannelScope dm = new ChannelScope(channel(), null);
    run(() -> members.addServerMember(s, member, now()));
    run(() -> members.addServerMember(s, member, now()));
    run(() -> members.addServerMember(s, other, now()));
    run(() -> members.addDmParticipants(dm.id(), member, stranger));

    assertThat(members.canAccess(member, serverChannel)).isTrue();
    assertThat(members.canAccess(stranger, serverChannel)).isFalse();
    assertThat(members.canAccess(stranger, dm)).isTrue();
    assertThat(members.canAccess(other, dm)).isFalse();
    run(() -> members.removeServerMember(s, member));
    assertThat(members.canAccess(member, serverChannel)).isFalse();
    assertThat(members.canAccess(other, serverChannel)).isTrue();
    run(() -> members.removeServer(s));
    assertThat(members.canAccess(other, serverChannel)).isFalse();
  }

  // Kịch bản: user rời server, chỉ state và mention của server đó bị xóa
  // Input: state + mention của u ở S1, S2 và của user khác ở S1; xóa (u,S1) ở cả hai kho
  // Output: còn đúng dòng của kênh S2
  @Test
  void deleteForUserInServerRemovesStateAndMentions() {
    UserId u = user();
    UserId other = user();
    ServerId s1 = server();
    ServerId s2 = server();
    ChannelId c1 = channel();
    ChannelId c2 = channel();
    seedStateAndMention(u, c1, s1);
    seedStateAndMention(other, c1, s1);
    seedStateAndMention(u, c2, s2);

    run(() -> readStates.deleteForUserInServer(u, s1));
    run(() -> mentions.deleteForUserInServer(u, s1));

    assertThat(channelsWithState(u)).containsExactly(c2.value());
    assertThat(channelsWithMention(u)).containsExactly(c2.value());
    assertThat(channelsWithState(other)).containsExactly(c1.value());
    assertThat(channelsWithMention(other)).containsExactly(c1.value());
  }

  // Kịch bản: server bị xóa, mọi state và mention của server đó bị xóa
  // Input: hai user có state + mention ở kênh S1, một user ở kênh S2; deleteForServer(S1)
  // Output: kênh S1 không còn dòng nào, kênh S2 còn nguyên
  @Test
  void deleteForServerRemovesAll() {
    UserId u1 = user();
    UserId u2 = user();
    ServerId s1 = server();
    ServerId s2 = server();
    ChannelId c1 = channel();
    ChannelId c2 = channel();
    seedStateAndMention(u1, c1, s1);
    seedStateAndMention(u2, c1, s1);
    seedStateAndMention(u1, c2, s2);

    run(() -> readStates.deleteForServer(s1));
    run(() -> mentions.deleteForServer(s1));

    assertThat(channelsWithState(u1)).containsExactly(c2.value());
    assertThat(channelsWithMention(u1)).containsExactly(c2.value());
    assertThat(channelsWithState(u2)).isEmpty();
    assertThat(channelsWithMention(u2)).isEmpty();
  }

  // Kịch bản: PUT đọc đua với mention mới đang giữ khóa dòng read_states
  // Input: T1 chèn mention m và addMentionCount rồi treo; T2 advance(b<m) chờ khóa; T1 commit
  // Output: T2 trả mentionCount = 1 và dòng read_states giữ 1 (không mất mention)
  @Test
  void advanceRecountSeesMentionCommittedWhileWaitingForRowLock() throws Exception {
    UserId u = user();
    ChannelId c = channel();
    MessageId a = message();
    MessageId b = message();
    MessageId m = message();
    call(() -> readStates.advance(u, c, a));
    CountDownLatch counted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> mention =
        CompletableFuture.runAsync(
            () ->
                run(
                    () -> {
                      mentions.insertIfAbsent(m, u, c, null, user(), now());
                      readStates.addMentionCount(u, c, m);
                      counted.countDown();
                      await(release);
                    }));
    assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<ReadStateSnapshot> read =
        CompletableFuture.supplyAsync(() -> call(() -> readStates.advance(u, c, b)));
    awaitLockWaiter();
    release.countDown();
    mention.get(10, TimeUnit.SECONDS);
    ReadStateSnapshot snapshot = read.get(10, TimeUnit.SECONDS);

    assertThat(snapshot).isEqualTo(new ReadStateSnapshot(b, 1));
    assertThat(
            jdbc.queryForObject(
                "select mention_count from notification.read_states"
                    + " where user_id = ? and channel_id = ?",
                Integer.class,
                u.value(),
                c.value()))
        .isEqualTo(1);
  }

  // Ghi một dòng read state và một mention cho user ở kênh của server
  private void seedStateAndMention(UserId u, ChannelId c, ServerId s) {
    MessageId m = message();
    run(() -> activity.recordMessage(c, s, m, now()));
    run(() -> mentions.insertIfAbsent(m, u, c, s, user(), now()));
    run(() -> readStates.addMentionCount(u, c, m));
  }

  // Chờ tới khi có phiên Postgres đang đợi khóa trên read_states
  private void awaitLockWaiter() throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      Integer waiting =
          jdbc.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                  + " and query ilike '%read_states%'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("advance never waited for the row lock");
  }

  // Đợi latch, chuyển InterruptedException thành lỗi không kiểm
  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // Chạy thao tác ghi trong một transaction
  private void run(Runnable action) {
    tx.executeWithoutResult(s -> action.run());
  }

  // Chạy thao tác có kết quả trong một transaction
  private <T> T call(Supplier<T> action) {
    return tx.execute(s -> action.get());
  }

  // Đọc last_message_id của kênh
  private UUID lastMessage(ChannelId c) {
    return jdbc.queryForObject(
        "select last_message_id from notification.channel_activity where channel_id = ?",
        UUID.class,
        c.value());
  }

  // Đọc last_message_at của kênh
  private Instant lastMessageAt(ChannelId c) {
    return jdbc.queryForObject(
            "select last_message_at from notification.channel_activity where channel_id = ?",
            java.sql.Timestamp.class,
            c.value())
        .toInstant();
  }

  // Đọc server_id của kênh
  private UUID serverOf(ChannelId c) {
    return jdbc.queryForObject(
        "select server_id from notification.channel_activity where channel_id = ?",
        UUID.class,
        c.value());
  }

  // Đếm dòng read_states của user
  private int readStateCount(UserId u) {
    return jdbc.queryForObject(
        "select count(*) from notification.read_states where user_id = ?",
        Integer.class,
        u.value());
  }

  // Liệt kê kênh có read state của user
  private List<UUID> channelsWithState(UserId u) {
    return jdbc.queryForList(
        "select channel_id from notification.read_states where user_id = ?", UUID.class, u.value());
  }

  // Liệt kê kênh có mention của user
  private List<UUID> channelsWithMention(UserId u) {
    return jdbc.queryForList(
        "select channel_id from notification.mentions where user_id = ?", UUID.class, u.value());
  }

  // Thời điểm hiện tại cắt tới micro giây cho khớp timestamptz
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  // UUIDv7 tăng dần cho tin nhắn
  private static MessageId message() {
    return new MessageId(UuidCreator.getTimeOrderedEpoch());
  }

  // Kênh ngẫu nhiên
  private static ChannelId channel() {
    return new ChannelId(UUID.randomUUID());
  }

  // Server ngẫu nhiên
  private static ServerId server() {
    return new ServerId(UUID.randomUUID());
  }

  // User ngẫu nhiên
  private static UserId user() {
    return new UserId(UUID.randomUUID());
  }
}

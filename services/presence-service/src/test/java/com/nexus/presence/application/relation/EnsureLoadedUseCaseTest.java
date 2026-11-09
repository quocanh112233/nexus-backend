package com.nexus.presence.application.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.presence.application.presence.FakeSessionStore;
import com.nexus.presence.application.presence.QueryPresenceUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Kiểm tra nạp bù có rào phiên bản: chặn snapshot cũ, thử lại 3 lần rồi fail-closed
class EnsureLoadedUseCaseTest {

  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final ApplyRelationEventUseCase events = new ApplyRelationEventUseCase(projection);
  private final UUID user = UUID.randomUUID();
  private final UUID peer = UUID.randomUUID();
  private final UUID server = UUID.randomUUID();
  private final List<Set<UUID>> snapshots = new ArrayList<>();
  private int calls;
  private IntConsumer duringFetch = n -> {};

  private final EnsureLoadedUseCase ensure =
      new EnsureLoadedUseCase(
          projection,
          id -> {
            Set<UUID> snapshot = snapshots.get(Math.min(calls, snapshots.size() - 1));
            duringFetch.accept(calls++);
            return snapshot;
          });

  // Áp event một lần duy nhất vào lần lấy snapshot đầu tiên
  private void eventOnFirstFetch(int n, String type, UUID serverId, UUID userId) {
    if (n == 0) {
      events.execute(type, serverId, userId);
    }
  }

  // Kịch bản: user bị kick giữa lúc lấy snapshot từ Community, snapshot lấy trước khi kick
  // Input: loader lần 1 trả {server} kèm MemberKicked xử lý trong callback, lần 2 trả {}
  // Output: loader gọi 2 lần, user không còn server và QueryPresenceUseCase không thấy peer
  @Test
  void staleSnapshotIsFencedAndRetried() {
    projection.load(peer, Set.of(server), projection.revision(peer));
    snapshots.addAll(List.of(Set.of(server), Set.of()));
    duringFetch = n -> eventOnFirstFetch(n, "MemberKicked", server, user);

    ensure.ensure(user);

    assertThat(calls).isEqualTo(2);
    assertThat(projection.isLoaded(user)).isTrue();
    assertThat(projection.serversOf(user)).isEmpty();
    var query = new QueryPresenceUseCase(ensure, projection, new FakeSessionStore());
    assertThat(query.execute(user, List.of(peer))).isEmpty();
  }

  // Kịch bản: event liên tục chen vào mọi lần lấy snapshot
  // Input: loader luôn kèm một event MemberJoined mới trong callback
  // Output: sau 3 lần ném RelationUnavailableException và không có gì được nạp
  @Test
  void givesUpAfterThreeFencedAttempts() {
    snapshots.add(Set.of(server));
    duringFetch = n -> events.execute("MemberJoined", UUID.randomUUID(), user);

    assertThatThrownBy(() -> ensure.ensure(user)).isInstanceOf(RelationUnavailableException.class);

    assertThat(calls).isEqualTo(3);
    assertThat(projection.isLoaded(user)).isFalse();
    assertThat(projection.serversOf(user)).isEmpty();
  }

  // Kịch bản: Join, Ban, Left đến khi user chưa loaded
  // Input: lần lượt MemberJoined, MemberBanned, MemberLeft cho user chưa nạp
  // Output: revision của user tăng sau từng event
  @ParameterizedTest
  @ValueSource(strings = {"MemberJoined", "MemberBanned", "MemberLeft"})
  void memberEventsBumpRevisionEvenWhenNotLoaded(String type) {
    long before = projection.revision(user);

    events.execute(type, server, user);

    assertThat(projection.revision(user)).isGreaterThan(before);
  }

  // Kịch bản: ServerDeleted chạy khi snapshot của user chưa có trong mirror members
  // Input: loader trả {server} kèm ServerDeleted(server) trong callback, lần 2 trả {}
  // Output: snapshot cũ bị chặn, loader gọi 2 lần và user không còn server
  @Test
  void serverDeletedFencesSnapshotOfUserNotInMirror() {
    snapshots.addAll(List.of(Set.of(server), Set.of()));
    duringFetch = n -> eventOnFirstFetch(n, "ServerDeleted", server, null);

    ensure.ensure(user);

    assertThat(calls).isEqualTo(2);
    assertThat(projection.serversOf(user)).isEmpty();
  }
}

package com.nexus.presence.application.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.FakeRelationshipProjection;
import com.nexus.presence.application.relation.RelationUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Unit test SessionService với store, projection và publisher giả
class SessionServiceTest {

  private final FakeSessionStore store = new FakeSessionStore();
  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final List<PresenceChange> published = new ArrayList<>();
  private boolean loaderDown = false;
  private final EnsureLoadedUseCase ensure =
      new EnsureLoadedUseCase(
          projection,
          id -> {
            if (loaderDown) {
              throw new RelationUnavailableException("down", null);
            }
            return Set.of();
          });
  private final SessionService service =
      new SessionService(
          store, new PresenceChangeNotifier(ensure, projection, published::add, Clock.systemUTC()));

  private final UUID user = UUID.randomUUID();
  private final Instant t0 = Instant.parse("2026-11-08T00:00:00Z");

  // Kịch bản: chỉ phát khi trạng thái thật sự đổi
  // Input: connect s1, connect s2, disconnect s1, disconnect s2, disconnect s2 lần nữa
  // Output: đúng hai bản ghi ONLINE rồi OFFLINE
  @Test
  void publishesOnlyOnRealTransition() {
    service.connect("s1", user, "i", t0);
    service.connect("s2", user, "i", t0);
    service.disconnect("s1");
    service.disconnect("s2");
    service.disconnect("s2");

    assertThat(published).extracting(PresenceChange::status).containsExactly("ONLINE", "OFFLINE");
  }

  // Kịch bản: SessionConnected bị mất, heartbeat liệt kê session đó
  // Input: heartbeat(i, {s1: user}) khi store chưa biết s1
  // Output: user ONLINE được publish và instance được đánh dấu sống
  @Test
  void heartbeatConnectsUnknownSession() {
    service.heartbeat("i", Map.of("s1", user), t0.plusSeconds(30));

    assertThat(published).extracting(PresenceChange::status).containsExactly("ONLINE");
    assertThat(store.deadInstances()).isEmpty();
  }

  // Kịch bản: SessionDisconnected bị mất, heartbeat không còn session
  // Input: connect s1 lúc t0 rồi heartbeat(i, {}) lúc t0+30s
  // Output: user OFFLINE được publish
  @Test
  void heartbeatDisconnectsSessionMissingFromIt() {
    service.connect("s1", user, "i", t0);

    service.heartbeat("i", Map.of(), t0.plusSeconds(30));

    assertThat(published).extracting(PresenceChange::status).containsExactly("ONLINE", "OFFLINE");
  }

  // Kịch bản: session mở sau thời điểm heartbeat được tạo
  // Input: connect s1 lúc t0+31s rồi heartbeat(i, {}) với sentAt t0+30s
  // Output: s1 không bị ngắt, chỉ có ONLINE
  @Test
  void heartbeatKeepsSessionConnectedAfterItWasSent() {
    service.connect("s1", user, "i", t0.plusSeconds(31));

    service.heartbeat("i", Map.of(), t0.plusSeconds(30));

    assertThat(published).extracting(PresenceChange::status).containsExactly("ONLINE");
  }

  // Kịch bản: heartbeat từ gateway cũ không có userId
  // Input: heartbeat(i, {s1: null}) với s1 chưa biết
  // Output: không connect, không publish
  @Test
  void heartbeatWithoutUserIdCannotConnect() {
    Map<String, UUID> sessions = new HashMap<>();
    sessions.put("s1", null);

    service.heartbeat("i", sessions, t0);

    assertThat(published).isEmpty();
  }

  // Kịch bản: user là member của hai server
  // Input: nạp projection {a, b} rồi connect
  // Output: payload có serverIds {a, b}, friendIds rỗng, changedAt có giá trị
  @Test
  void serverIdsFromProjectionAreInPayload() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    projection.load(user, Set.of(a, b));

    service.connect("s1", user, "i", t0);

    PresenceChange change = published.get(0);
    assertThat(change.serverIds()).containsExactlyInAnyOrder(a, b);
    assertThat(change.friendIds()).isEmpty();
    assertThat(change.changedAt()).isNotNull();
  }

  // Kịch bản: gRPC Community lỗi khi nạp bù, projection chưa có server nào của user
  // Input: loader ném RelationUnavailableException rồi connect
  // Output: vẫn publish ONLINE với serverIds rỗng (đúng những gì projection đang có)
  @Test
  void gRpcFailureStillPublishesWithKnownServers() {
    loaderDown = true;

    service.connect("s1", user, "i", t0);

    assertThat(published).hasSize(1);
    assertThat(published.get(0).status()).isEqualTo("ONLINE");
    assertThat(published.get(0).serverIds()).isEmpty();
  }

  // Kịch bản: đọc projection lỗi hạ tầng sau khi store đã đổi trạng thái
  // Input: projection ném IllegalStateException ở serversOf rồi connect
  // Output: vẫn publish ONLINE với serverIds rỗng, không ném lỗi
  @Test
  void projectionReadFailureStillPublishesWithEmptyServers() {
    FakeRelationshipProjection broken =
        new FakeRelationshipProjection() {
          // Mô phỏng Redis lỗi khi đọc server
          @Override
          public Set<UUID> serversOf(UUID userId) {
            throw new IllegalStateException("redis down");
          }
        };
    SessionService svc =
        new SessionService(
            store,
            new PresenceChangeNotifier(
                new EnsureLoadedUseCase(broken, id -> Set.of()),
                broken,
                published::add,
                Clock.systemUTC()));

    svc.connect("s1", user, "i", t0);

    assertThat(published).hasSize(1);
    assertThat(published.get(0).serverIds()).isEmpty();
  }
}

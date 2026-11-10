package com.nexus.presence.application.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.FakeRelationshipProjection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Unit test PurgeDeadInstancesUseCase với store, projection và publisher giả
class PurgeDeadInstancesUseCaseTest {

  private final List<PresenceChange> published = new ArrayList<>();
  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final UUID user = UUID.randomUUID();

  // Kịch bản: instance chết có user chỉ ở đó
  // Input: connect s1 cho instance chưa heartbeat rồi chạy execute
  // Output: phát OFFLINE của user
  @Test
  void deadInstanceUsersGoOffline() {
    FakeSessionStore store = new FakeSessionStore();
    store.connect("s1", user, "i", Instant.now());

    useCase(store).execute();

    assertThat(published).extracting(PresenceChange::status).containsExactly("OFFLINE");
  }

  // Kịch bản: heartbeat đến giữa lúc phát hiện chết và lúc purge
  // Input: store giả đánh dấu instance sống ngay sau khi deadInstances trả về, rồi execute
  // Output: không phát OFFLINE và session vẫn còn
  @Test
  void heartbeatBetweenDetectionAndPurgeKeepsInstanceAlive() {
    FakeSessionStore store =
        new FakeSessionStore() {
          // Trả instance chết rồi mô phỏng heartbeat đến ngay sau đó
          @Override
          public Set<String> deadInstances() {
            Set<String> dead = super.deadInstances();
            dead.forEach(this::markAlive);
            return dead;
          }
        };
    store.connect("s1", user, "i", Instant.now());

    useCase(store).execute();

    assertThat(published).isEmpty();
    assertThat(store.sessionsOf("i", Instant.now())).containsExactly("s1");
  }

  // Dựng use case với notifier ghi lại thay đổi vào danh sách published
  private PurgeDeadInstancesUseCase useCase(FakeSessionStore store) {
    EnsureLoadedUseCase ensure =
        new EnsureLoadedUseCase(projection, id -> Set.of(), id -> Set.of());
    return new PurgeDeadInstancesUseCase(
        store, new PresenceChangeNotifier(ensure, projection, published::add, Clock.systemUTC()));
  }
}

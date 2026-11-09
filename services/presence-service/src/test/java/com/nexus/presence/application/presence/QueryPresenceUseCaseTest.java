package com.nexus.presence.application.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.FakeRelationshipProjection;
import com.nexus.presence.application.relation.RelationUnavailableException;
import com.nexus.presence.application.relation.ServerListLoader;
import com.nexus.presence.domain.presence.PresenceStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra lọc target theo quan hệ, giới hạn gọi loader và fail-closed của QueryPresenceUseCase
class QueryPresenceUseCaseTest {

  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final List<UUID> loaderCalls = new ArrayList<>();
  private final Set<UUID> failing = new HashSet<>();
  private final UUID server = UUID.randomUUID();
  private final UUID viewer = UUID.randomUUID();

  private final ServerListLoader loader =
      userId -> {
        loaderCalls.add(userId);
        if (failing.contains(userId)) {
          throw new RelationUnavailableException("down", null);
        }
        return Set.of(server);
      };

  private final QueryPresenceUseCase useCase =
      new QueryPresenceUseCase(
          new EnsureLoadedUseCase(projection, loader), projection, new FakeSessionStore());

  // Kịch bản: viewer đã nạp nhưng không ở server nào, các target chưa nạp
  // Input: viewer {} xem 3 target chưa loaded
  // Output: kết quả rỗng và loader không bị gọi lần nào
  @Test
  void viewerWithNoServersCausesZeroLoaderCalls() {
    projection.load(viewer, Set.of());

    var result = useCase.execute(viewer, List.of(user(), user(), user()));

    assertThat(result).isEmpty();
    assertThat(loaderCalls).isEmpty();
  }

  // Kịch bản: loader hỏng ở target chưa nạp đầu tiên, giữa danh sách có target đã nạp
  // Input: viewer {s}, target u1 chưa nạp (hỏng), l đã nạp {s}, u2 và u3 chưa nạp
  // Output: loader gọi đúng 1 lần (cho u1) và kết quả chỉ có l
  @Test
  void loaderFailureOnFirstUnloadedTargetStopsFurtherLoaderCalls() {
    projection.load(viewer, Set.of(server));
    UUID loaded = user();
    projection.load(loaded, Set.of(server));
    UUID u1 = user();
    UUID u2 = user();
    UUID u3 = user();
    failing.addAll(List.of(u1, u2, u3));

    var result = useCase.execute(viewer, List.of(u1, loaded, u2, u3));

    assertThat(loaderCalls).containsExactly(u1);
    assertThat(result).containsOnlyKeys(loaded);
  }

  // Kịch bản: mọi target chưa nạp đều lỗi nhưng danh sách có chính viewer
  // Input: viewer {s} xem [u chưa nạp (hỏng), viewer]
  // Output: kết quả chỉ có viewer với status OFFLINE
  @Test
  void selfStillVisibleWhenTargetsFail() {
    projection.load(viewer, Set.of(server));
    UUID u = user();
    failing.add(u);

    var result = useCase.execute(viewer, List.of(u, viewer));

    assertThat(result).containsOnlyKeys(viewer);
    assertThat(result.get(viewer)).isEqualTo(PresenceStatus.OFFLINE);
  }

  // Kịch bản: một target nạp lỗi, target khác cùng server với viewer
  // Input: viewer {s} xem [bad nạp lỗi, ok đã nạp {s}], loader chỉ hỏng với bad
  // Output: bad bị loại, ok có mặt
  @Test
  void failingTargetOmittedAndVisibleTargetReturned() {
    projection.load(viewer, Set.of(server));
    UUID ok = user();
    UUID bad = user();
    failing.add(bad);
    projection.load(ok, Set.of(server));

    var result = useCase.execute(viewer, List.of(bad, ok));

    assertThat(result).containsOnlyKeys(ok);
  }

  // Id user ngẫu nhiên
  private static UUID user() {
    return UUID.randomUUID();
  }
}

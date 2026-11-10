package com.nexus.realtime.application.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.realtime.application.subscription.AccessChecker;
import com.nexus.realtime.application.subscription.RevocationService;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import com.nexus.testsupport.ContractFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Chạy mọi fixture contracts/events qua EventRouter và RevocationService để bắt lệch payload
class FixtureRoutingTest {

  private static final Path EVENTS = Path.of("..", "..", "contracts", "events");
  private static final Set<String> TWO_ROUTES =
      Set.of(
          "MemberJoined",
          "MemberKicked",
          "MemberBanned",
          "PresenceChanged",
          "FriendRequestAccepted",
          "FriendRemoved",
          "DmChannelCreated");
  private static final Set<String> NO_ROUTES =
      Set.of(
          "GatewayHeartbeat",
          "SessionConnected",
          "SessionDisconnected",
          "UserProfileUpdated",
          "UserRegistered");

  private final RevocationService revocation =
      new RevocationService(
          new SubscriptionRegistry(), mock(AccessChecker.class), (s, reason) -> {});

  // Tên mọi fixture trong contracts/events, bỏ đuôi .json
  private static Stream<String> fixtures() throws IOException {
    try (Stream<Path> files = Files.list(EVENTS)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".json"))
          .map(n -> n.substring(0, n.length() - 5))
          .sorted()
          .toList()
          .stream();
    }
  }

  // Số route mong đợi: 2 cho event giao thêm user hoặc presence, 0 cho event nội bộ, còn lại 1
  private static int expected(String type) {
    return TWO_ROUTES.contains(type) ? 2 : NO_ROUTES.contains(type) ? 0 : 1;
  }

  // Kịch bản: mỗi fixture hợp đồng (kể cả PresenceChanged) đi qua router và revocation
  // Input: payload mẫu của contracts/events/<type>.json bọc trong envelope
  // Output: không ném lỗi và số route đúng bảng (event ngoài bảng không có route)
  @ParameterizedTest
  @MethodSource("fixtures")
  void everyFixtureRoutesWithoutThrowing(String type) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            type,
            1,
            Instant.now(),
            "test",
            "Agg",
            UUID.randomUUID(),
            ContractFixtures.payload(type));

    assertThatCode(() -> revocation.onEvent(envelope)).doesNotThrowAnyException();
    assertThat(EventRouter.route(envelope)).hasSize(expected(type));
  }
}

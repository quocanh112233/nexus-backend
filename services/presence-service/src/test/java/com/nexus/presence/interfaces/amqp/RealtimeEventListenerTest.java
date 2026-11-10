package com.nexus.presence.interfaces.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.presence.application.presence.FakeSessionStore;
import com.nexus.presence.application.presence.PresenceChange;
import com.nexus.presence.application.presence.PresenceChangeNotifier;
import com.nexus.presence.application.presence.SessionService;
import com.nexus.presence.application.relation.EnsureLoadedUseCase;
import com.nexus.presence.application.relation.FakeRelationshipProjection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

// Unit test RealtimeEventListener nối với SessionService thật trên store giả
class RealtimeEventListenerTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final FakeSessionStore store = new FakeSessionStore();
  private final FakeRelationshipProjection projection = new FakeRelationshipProjection();
  private final List<PresenceChange> published = new ArrayList<>();
  private final RealtimeEventListener listener =
      new RealtimeEventListener(
          new EventEnvelopeReader(MAPPER),
          new SessionService(
              store,
              new PresenceChangeNotifier(
                  new EnsureLoadedUseCase(projection, id -> Set.of(), id -> Set.of()),
                  projection,
                  published::add,
                  Clock.systemUTC())));

  // Kịch bản: heartbeat có userIds lệch độ dài với sessionIds
  // Input: s-old đang mở, heartbeat liệt kê s-new với userIds rỗng, sentAt sau 30 giây
  // Output: không connect s-new, s-old vẫn bị ngắt (ONLINE rồi OFFLINE của user cũ)
  @Test
  void heartbeatWithMismatchedUserIdsOnlyDisconnects() {
    UUID user = UUID.randomUUID();
    Instant t0 = Instant.parse("2026-11-08T00:00:00Z");
    store.connect("s-old", user, "i", t0);
    published.add(new PresenceChange(user, "ONLINE", t0, List.of(), List.of()));

    listener.on(
        message(
            Map.of(
                "instanceId",
                "i",
                "sessionIds",
                List.of("s-new"),
                "userIds",
                List.of(),
                "sentAt",
                t0.plusSeconds(30).toString())));

    assertThat(store.sessionsOf("i", t0.plusSeconds(60))).isEmpty();
    assertThat(published).extracting(PresenceChange::status).containsExactly("ONLINE", "OFFLINE");
  }

  // Bọc payload heartbeat vào message AMQP
  private static Message message(Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            "GatewayHeartbeat",
            1,
            Instant.now(),
            "realtime-gateway",
            "Gateway",
            UUID.randomUUID(),
            payload);
    return MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope)).build();
  }
}

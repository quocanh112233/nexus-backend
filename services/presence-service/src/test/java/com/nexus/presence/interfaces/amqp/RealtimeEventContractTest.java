package com.nexus.presence.interfaces.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.presence.application.presence.SessionService;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra RealtimeEventListener đọc được các fixture event của realtime-gateway
class RealtimeEventContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String USER = "0192f0c4-7a1e-7c3d-9a1b-000000000001";
  private static final String SESSION = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

  private final SessionService service = mock(SessionService.class);
  private final RealtimeEventListener listener =
      new RealtimeEventListener(new EventEnvelopeReader(MAPPER), service);

  // Kịch bản: fixture SessionConnected đi qua listener
  // Input: envelope bọc payload của SessionConnected.json
  // Output: SessionService.connect nhận đúng session, user, instance và connectedAt của fixture
  @Test
  void sessionConnectedFixtureParses() {
    listener.on(message("SessionConnected"));

    verify(service)
        .connect(
            SESSION, UUID.fromString(USER), "gw-1", Instant.parse("2026-11-08T01:02:03.123456Z"));
  }

  // Kịch bản: fixture SessionDisconnected đi qua listener
  // Input: envelope bọc payload của SessionDisconnected.json
  // Output: SessionService.disconnect nhận đúng sessionId
  @Test
  void sessionDisconnectedFixtureParses() {
    listener.on(message("SessionDisconnected"));

    verify(service).disconnect(SESSION);
  }

  // Kịch bản: fixture GatewayHeartbeat đi qua listener
  // Input: envelope bọc payload của GatewayHeartbeat.json
  // Output: SessionService.heartbeat nhận instance, map session-user và sentAt của fixture
  @Test
  void gatewayHeartbeatFixtureParses() {
    listener.on(message("GatewayHeartbeat"));

    verify(service)
        .heartbeat(
            "gw-1",
            Map.of(SESSION, UUID.fromString(USER)),
            Instant.parse("2026-11-08T01:02:33.123456Z"));
  }

  // Kịch bản: fixture TypingStarted không thuộc presence
  // Input: envelope bọc payload của TypingStarted.json
  // Output: listener bỏ qua, không gọi SessionService
  @Test
  void typingStartedFixtureIsIgnored() {
    listener.on(message("TypingStarted"));

    verify(service, never()).connect(any(), any(), any(), any());
    verify(service, never()).disconnect(any());
    verify(service, never()).heartbeat(any(), any(), any());
  }

  // Bọc payload fixture vào envelope JSON dạng message AMQP
  private static Message message(String eventType) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            eventType,
            1,
            Instant.now(),
            "realtime-gateway",
            "Session",
            UUID.randomUUID(),
            ContractFixtures.payload(eventType));
    return MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope)).build();
  }
}

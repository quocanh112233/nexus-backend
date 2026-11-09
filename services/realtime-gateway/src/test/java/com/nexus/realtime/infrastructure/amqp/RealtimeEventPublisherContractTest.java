package com.nexus.realtime.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.testsupport.ContractFixtures;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra payload RealtimeEventPublisher khớp fixture trong contracts/events
class RealtimeEventPublisherContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final RealtimeEventPublisher publisher =
      new RealtimeEventPublisher(rabbit, MAPPER, Clock.systemUTC());

  // Kịch bản: payload SessionConnected thật so với fixture
  // Input: publishSessionConnected
  // Output: cùng tập khóa và kiểu với SessionConnected.json
  @Test
  void sessionConnectedMatchesFixture() {
    publisher.publishSessionConnected("s", UUID.randomUUID(), "i", Instant.now());

    ContractFixtures.assertSameShape(sentPayload(), "SessionConnected");
  }

  // Kịch bản: payload SessionDisconnected thật so với fixture
  // Input: publishSessionDisconnected
  // Output: cùng tập khóa và kiểu với SessionDisconnected.json
  @Test
  void sessionDisconnectedMatchesFixture() {
    publisher.publishSessionDisconnected("s", UUID.randomUUID(), "i");

    ContractFixtures.assertSameShape(sentPayload(), "SessionDisconnected");
  }

  // Kịch bản: payload GatewayHeartbeat thật so với fixture
  // Input: publishHeartbeat với một session
  // Output: cùng tập khóa và kiểu với GatewayHeartbeat.json
  @Test
  void gatewayHeartbeatMatchesFixture() {
    publisher.publishHeartbeat("i", Map.of("s", UUID.randomUUID()), Instant.now());

    ContractFixtures.assertSameShape(sentPayload(), "GatewayHeartbeat");
  }

  // Kịch bản: payload TypingStarted thật so với fixture
  // Input: publishTyping
  // Output: cùng tập khóa và kiểu với TypingStarted.json
  @Test
  void typingStartedMatchesFixture() {
    publisher.publishTyping(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

    ContractFixtures.assertSameShape(sentPayload(), "TypingStarted");
  }

  // Kịch bản: heartbeat nhiều session giữ userIds cùng thứ tự với sessionIds
  // Input: publishHeartbeat với hai session
  // Output: userIds[i] là user của sessionIds[i]
  @Test
  void heartbeatKeepsUserIdsAlignedWithSessionIds() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    publisher.publishHeartbeat("i", Map.of("sa", a, "sb", b), Instant.now());

    Map<String, Object> payload = sentPayload();
    List<?> sessionIds = (List<?>) payload.get("sessionIds");
    List<?> userIds = (List<?>) payload.get("userIds");
    assertThat(userIds)
        .isEqualTo(sessionIds.stream().map(s -> (s.equals("sa") ? a : b).toString()).toList());
  }

  // Lấy payload của envelope đã gửi qua template giả
  private Map<String, Object> sentPayload() {
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).send(anyString(), anyString(), sent.capture());
    Map<String, Object> envelope =
        MAPPER.readValue(sent.getValue().getBody(), new TypeReference<Map<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
    return payload;
  }
}

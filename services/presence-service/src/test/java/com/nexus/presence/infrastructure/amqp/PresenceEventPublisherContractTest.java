package com.nexus.presence.infrastructure.amqp;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.presence.application.presence.PresenceChange;
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

// Kiểm tra payload PresenceEventPublisher khớp fixture PresenceChanged trong contracts/events
class PresenceEventPublisherContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  // Kịch bản: payload PresenceChanged thật so với fixture
  // Input: publish một PresenceChange có một server và một bạn
  // Output: cùng tập khóa và kiểu với PresenceChanged.json
  @Test
  void presenceChangedMatchesFixture() {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    new PresenceEventPublisher(rabbit, MAPPER, Clock.systemUTC())
        .publish(
            new PresenceChange(
                UUID.randomUUID(),
                "ONLINE",
                Instant.now(),
                List.of(UUID.randomUUID()),
                List.of(UUID.randomUUID())));

    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).send(anyString(), anyString(), sent.capture());
    Map<String, Object> envelope =
        MAPPER.readValue(sent.getValue().getBody(), new TypeReference<Map<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
    ContractFixtures.assertSameShape(payload, "PresenceChanged");
  }
}

package com.nexus.messaging.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.messaging.domain.message.Attachment;
import com.nexus.messaging.domain.message.MessageCreated;
import com.nexus.messaging.domain.message.MessageDeleted;
import com.nexus.messaging.domain.message.MessageEdited;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.ReactionAdded;
import com.nexus.messaging.domain.reaction.ReactionRemoved;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

// Kiểm tra payload messaging.events khớp fixture trong contracts/events
class OutboxEventContractTest {

  // Các event messaging: tên, routing key và event miền mẫu (trường nullable có giá trị)
  static Stream<Arguments> events() {
    MessageId id = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UUID server = UUID.randomUUID();
    UserId user = new UserId(UUID.randomUUID());
    Instant at = Instant.parse("2026-11-07T10:00:00Z");
    Attachment file =
        new Attachment(UUID.randomUUID(), "pid", "https://x/y.png", "image/png", 10, "y.png");
    return Stream.of(
        Arguments.of(
            "MessageCreated",
            "message.created",
            new MessageCreated(
                id,
                channel,
                server,
                user,
                "hi",
                MessageId.newId(),
                List.of(file),
                Set.of(user),
                at,
                new Nonce("n-1"))),
        Arguments.of(
            "MessageEdited",
            "message.edited",
            new MessageEdited(id, channel, server, "new", Set.of(user), at, 2)),
        Arguments.of(
            "MessageDeleted", "message.deleted", new MessageDeleted(id, channel, server, user)),
        Arguments.of(
            "ReactionAdded",
            "reaction.added",
            new ReactionAdded(id, channel, user, new Emoji("👍"))),
        Arguments.of(
            "ReactionRemoved",
            "reaction.removed",
            new ReactionRemoved(id, channel, user, new Emoji("👍"))));
  }

  // Kịch bản: payload publisher phát ra cùng khóa và kiểu với fixture của event đó
  // Input: event miền mẫu theo eventType đi qua OutboxDomainEventPublisher
  // Output: routing key đúng catalog và assertSameShape không lỗi
  @ParameterizedTest
  @MethodSource("events")
  void payloadMatchesContract(String eventType, String routingKey, DomainEvent event) {
    OutboxWriter writer = mock(OutboxWriter.class);

    new OutboxDomainEventPublisher(writer).publish(event);

    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer).append(captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo(eventType);
    assertThat(captor.getValue().routingKey()).isEqualTo(routingKey);
    ContractFixtures.assertSameShape(captor.getValue().payload(), eventType);
  }
}

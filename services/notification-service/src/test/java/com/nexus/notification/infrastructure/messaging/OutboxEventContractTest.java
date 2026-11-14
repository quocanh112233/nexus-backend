package com.nexus.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.notification.domain.readstate.MentionCreated;
import com.nexus.notification.domain.readstate.ReadStateUpdated;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.DomainEvent;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import com.nexus.testsupport.ContractFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra payload notification.events khớp fixture trong contracts/events
class OutboxEventContractTest {

  private final UserId user = new UserId(UUID.randomUUID());
  private final UserId author = new UserId(UUID.randomUUID());
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final MessageId message = new MessageId(UUID.randomUUID());
  private final OutboxWriter writer = mock(OutboxWriter.class);
  private final OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);

  // Kịch bản: ReadStateUpdated đi qua publisher
  // Input: ReadStateUpdated(user, channel, message, 2)
  // Output: aggregate ReadState/userId, routing key read-state.updated, payload khớp fixture
  @Test
  void readStateUpdatedMatchesContract() {
    publisher.publish(new ReadStateUpdated(user, channel, message, 2));

    OutboxMessage sent = captured(1).get(0);
    assertThat(sent.aggregateType()).isEqualTo("ReadState");
    assertThat(sent.aggregateId()).isEqualTo(user.value());
    assertThat(sent.eventType()).isEqualTo("ReadStateUpdated");
    assertThat(sent.eventVersion()).isEqualTo(1);
    assertThat(sent.routingKey()).isEqualTo("read-state.updated");
    ContractFixtures.assertSameShape(sent.payload(), "ReadStateUpdated");
    assertThat(sent.payload())
        .containsEntry("userId", user.value().toString())
        .containsEntry("lastReadMessageId", message.value().toString())
        .containsEntry("mentionCount", 2);
  }

  // Kịch bản: MentionCreated ở kênh DM rồi ở kênh server
  // Input: MentionCreated serverId null rồi serverId của server
  // Output: aggregate Mention/messageId, routing key mention.created, khớp fixture, khóa serverId
  @Test
  void mentionCreatedMatchesContractWithNullableServer() {
    ServerId server = new ServerId(UUID.randomUUID());

    publisher.publish(new MentionCreated(user, channel, null, message, author, 1));
    publisher.publish(new MentionCreated(user, channel, server, message, author, 1));

    List<OutboxMessage> sent = captured(2);
    for (OutboxMessage m : sent) {
      assertThat(m.aggregateType()).isEqualTo("Mention");
      assertThat(m.aggregateId()).isEqualTo(message.value());
      assertThat(m.eventType()).isEqualTo("MentionCreated");
      assertThat(m.routingKey()).isEqualTo("mention.created");
      ContractFixtures.assertSameShape(m.payload(), "MentionCreated");
      assertThat(m.payload()).containsEntry("authorId", author.value().toString());
    }
    assertThat(sent.get(0).payload()).containsEntry("serverId", null);
    assertThat(sent.get(1).payload()).containsEntry("serverId", server.value().toString());
  }

  // Kịch bản: event chưa map
  // Input: một DomainEvent ẩn danh
  // Output: IllegalArgumentException
  @Test
  void unknownEventRejected() {
    assertThatThrownBy(() -> publisher.publish(new DomainEvent() {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Lấy các OutboxMessage đã ghi, khẳng định đúng số lần
  private List<OutboxMessage> captured(int times) {
    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer, times(times)).append(captor.capture());
    return captor.getAllValues();
  }
}

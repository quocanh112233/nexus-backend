package com.nexus.messaging.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.nexus.messaging.domain.social.DmChannelCreated;
import com.nexus.messaging.domain.social.FriendRemoved;
import com.nexus.messaging.domain.social.FriendRequestAccepted;
import com.nexus.messaging.domain.social.FriendRequestCanceled;
import com.nexus.messaging.domain.social.FriendRequestDeclined;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestSent;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
    UserId friend = new UserId(UUID.randomUUID());
    FriendRequestId request = FriendRequestId.newId();
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
            new ReactionRemoved(id, channel, user, new Emoji("👍"))),
        Arguments.of(
            "FriendRequestSent",
            "friend-request.sent",
            new FriendRequestSent(request, user, friend)),
        Arguments.of(
            "FriendRequestAccepted",
            "friend-request.accepted",
            new FriendRequestAccepted(request, user, friend)),
        Arguments.of(
            "FriendRequestDeclined",
            "friend-request.declined",
            new FriendRequestDeclined(request, user, friend)),
        Arguments.of(
            "FriendRequestCanceled",
            "friend-request.canceled",
            new FriendRequestCanceled(request, user, friend)),
        Arguments.of("FriendRemoved", "friend.removed", new FriendRemoved(user, friend)),
        Arguments.of(
            "DmChannelCreated", "dm-channel.created", new DmChannelCreated(channel, user, friend)));
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

  // Kịch bản: ba sự kiện tin ở kênh DM (serverId null) và ở kênh server
  // Input: MessageCreated/Edited/Deleted với serverId null rồi với serverId của server
  // Output: DM khớp hợp đồng và có khóa serverId mang null; kênh server có serverId dạng chuỗi
  @Test
  void messagePayloadServerIdNullForDmStringForServer() {
    MessageId id = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UserId user = new UserId(UUID.randomUUID());
    Instant at = Instant.parse("2026-11-07T10:00:00Z");
    UUID server = UUID.randomUUID();
    for (UUID serverId : new UUID[] {null, server}) {
      OutboxWriter writer = mock(OutboxWriter.class);
      OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);

      publisher.publish(
          new MessageCreated(
              id, channel, serverId, user, "hi", null, List.of(), Set.of(), at, null));
      publisher.publish(new MessageEdited(id, channel, serverId, "new", Set.of(), at, 2));
      publisher.publish(new MessageDeleted(id, channel, serverId, user));

      ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
      verify(writer, times(3)).append(captor.capture());
      for (OutboxMessage sent : captor.getAllValues()) {
        ContractFixtures.assertSameShape(sent.payload(), sent.eventType());
        assertThat(sent.payload())
            .containsEntry("serverId", serverId == null ? null : server.toString());
      }
    }
  }

  // Kịch bản: giá trị payload sự kiện bạn bè và DM đúng catalog, aggregate đúng loại
  // Input: FriendRequestAccepted, FriendRemoved và DmChannelCreated mẫu qua publisher
  // Output: id chuỗi đúng, aggregateType FriendRequest/Friendship/DmChannel, 2 participantIds
  @Test
  void socialPayloadValues() {
    OutboxWriter writer = mock(OutboxWriter.class);
    OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);
    UserId a = new UserId(UUID.randomUUID());
    UserId b = new UserId(UUID.randomUUID());
    FriendRequestId request = FriendRequestId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());

    publisher.publish(new FriendRequestAccepted(request, a, b));
    publisher.publish(new FriendRemoved(a, b));
    publisher.publish(new DmChannelCreated(channel, a, b));

    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer, times(3)).append(captor.capture());
    List<OutboxMessage> sent = captor.getAllValues();
    assertThat(sent.get(0).aggregateType()).isEqualTo("FriendRequest");
    assertThat(sent.get(0).aggregateId()).isEqualTo(request.value());
    assertThat(sent.get(0).payload())
        .containsEntry("requestId", request.value().toString())
        .containsEntry("requesterId", a.value().toString())
        .containsEntry("addresseeId", b.value().toString());
    assertThat(sent.get(1).aggregateType()).isEqualTo("Friendship");
    assertThat(sent.get(1).payload())
        .containsEntry("userId", a.value().toString())
        .containsEntry("friendId", b.value().toString());
    assertThat(sent.get(2).aggregateType()).isEqualTo("DmChannel");
    assertThat(sent.get(2).aggregateId()).isEqualTo(channel.value());
    assertThat(sent.get(2).payload())
        .containsEntry("channelId", channel.value().toString())
        .containsEntry("participantIds", List.of(a.value().toString(), b.value().toString()));
  }
}

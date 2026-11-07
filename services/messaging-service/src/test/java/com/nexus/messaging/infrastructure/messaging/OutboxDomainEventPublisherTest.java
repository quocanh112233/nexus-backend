package com.nexus.messaging.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra OutboxDomainEventPublisher ghim tên trường, routing key và aggregate type theo catalog
class OutboxDomainEventPublisherTest {

  private final OutboxWriter writer = mock(OutboxWriter.class);
  private final OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);

  // Kịch bản: MessageCreated ghi vào outbox đúng aggregate, routing key và payload
  // Input: tin có reply, một mention, một attachment và nonce
  // Output: aggregate Message, message.created, payload đủ 10 trường với id dạng chuỗi
  @Test
  void mapsMessageCreated() {
    MessageId id = MessageId.newId();
    MessageId reply = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UUID serverId = UUID.randomUUID();
    UserId author = new UserId(UUID.randomUUID());
    UserId mentioned = new UserId(UUID.randomUUID());
    Instant at = Instant.parse("2026-11-07T10:00:00Z");
    Attachment file =
        new Attachment(UUID.randomUUID(), "pid", "https://x/y.png", "image/png", 10, "y.png");

    OutboxMessage m =
        publish(
            new MessageCreated(
                id,
                channel,
                serverId,
                author,
                "hi",
                reply,
                List.of(file),
                Set.of(mentioned),
                at,
                new Nonce("n-1")));

    assertThat(m.aggregateType()).isEqualTo("Message");
    assertThat(m.aggregateId()).isEqualTo(id.value());
    assertThat(m.eventType()).isEqualTo("MessageCreated");
    assertThat(m.eventVersion()).isEqualTo(1);
    assertThat(m.routingKey()).isEqualTo("message.created");
    assertThat(m.payload())
        .containsOnlyKeys(
            "messageId",
            "channelId",
            "serverId",
            "authorId",
            "content",
            "replyToId",
            "attachments",
            "mentionUserIds",
            "createdAt",
            "nonce")
        .containsEntry("messageId", id.value().toString())
        .containsEntry("channelId", channel.value().toString())
        .containsEntry("serverId", serverId.toString())
        .containsEntry("authorId", author.value().toString())
        .containsEntry("content", "hi")
        .containsEntry("replyToId", reply.value().toString())
        .containsEntry("mentionUserIds", List.of(mentioned.value().toString()))
        .containsEntry("createdAt", "2026-11-07T10:00:00Z")
        .containsEntry("nonce", "n-1");
    assertThat(m.payload().get("attachments"))
        .isEqualTo(
            List.of(
                java.util.Map.of(
                    "publicId", "pid",
                    "url", "https://x/y.png",
                    "contentType", "image/png",
                    "bytes", 10L)));
  }

  // Kịch bản: tin không reply, không nonce
  // Input: MessageCreated với replyToId và nonce null
  // Output: payload để null cho hai trường đó
  @Test
  void mapsOptionalFieldsAsNull() {
    OutboxMessage m =
        publish(
            new MessageCreated(
                MessageId.newId(),
                new ChannelId(UUID.randomUUID()),
                UUID.randomUUID(),
                new UserId(UUID.randomUUID()),
                "hi",
                null,
                List.of(),
                Set.of(),
                Instant.parse("2026-11-07T10:00:00Z"),
                null));

    assertThat(m.payload()).containsEntry("replyToId", null).containsEntry("nonce", null);
  }

  // Kịch bản: MessageEdited ghi vào outbox đúng routing key và payload
  // Input: sự kiện sửa tin version 4 với một mention
  // Output: message.edited, payload đủ 7 trường với id dạng chuỗi và editedAt ISO
  @Test
  void mapsMessageEdited() {
    MessageId id = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UUID serverId = UUID.randomUUID();
    UserId mentioned = new UserId(UUID.randomUUID());

    OutboxMessage m =
        publish(
            new MessageEdited(
                id,
                channel,
                serverId,
                "new",
                Set.of(mentioned),
                Instant.parse("2026-11-07T10:00:00Z"),
                4));

    assertThat(m.aggregateType()).isEqualTo("Message");
    assertThat(m.aggregateId()).isEqualTo(id.value());
    assertThat(m.eventType()).isEqualTo("MessageEdited");
    assertThat(m.routingKey()).isEqualTo("message.edited");
    assertThat(m.payload())
        .containsOnlyKeys(
            "messageId",
            "channelId",
            "serverId",
            "content",
            "mentionUserIds",
            "editedAt",
            "version")
        .containsEntry("messageId", id.value().toString())
        .containsEntry("channelId", channel.value().toString())
        .containsEntry("serverId", serverId.toString())
        .containsEntry("content", "new")
        .containsEntry("mentionUserIds", List.of(mentioned.value().toString()))
        .containsEntry("editedAt", "2026-11-07T10:00:00Z")
        .containsEntry("version", 4L);
  }

  // Kịch bản: MessageDeleted ghi vào outbox đúng routing key và payload
  // Input: sự kiện xóa tin có deletedBy
  // Output: message.deleted, payload đủ 4 trường với id dạng chuỗi
  @Test
  void mapsMessageDeleted() {
    MessageId id = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UUID serverId = UUID.randomUUID();
    UserId by = new UserId(UUID.randomUUID());

    OutboxMessage m = publish(new MessageDeleted(id, channel, serverId, by));

    assertThat(m.aggregateId()).isEqualTo(id.value());
    assertThat(m.eventType()).isEqualTo("MessageDeleted");
    assertThat(m.routingKey()).isEqualTo("message.deleted");
    assertThat(m.payload())
        .containsOnlyKeys("messageId", "channelId", "serverId", "deletedBy")
        .containsEntry("messageId", id.value().toString())
        .containsEntry("channelId", channel.value().toString())
        .containsEntry("serverId", serverId.toString())
        .containsEntry("deletedBy", by.value().toString());
  }

  // Kịch bản: ReactionAdded và ReactionRemoved ghi vào outbox theo catalog
  // Input: một sự kiện mỗi loại với tin, kênh, user và emoji "👍"
  // Output: aggregate Reaction, id là messageId, routing key reaction.*, payload 4 trường
  @Test
  void mapsReactionEvents() {
    MessageId id = MessageId.newId();
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UserId user = new UserId(UUID.randomUUID());

    OutboxMessage added = publish(new ReactionAdded(id, channel, user, new Emoji("👍")));

    assertThat(added.aggregateType()).isEqualTo("Reaction");
    assertThat(added.aggregateId()).isEqualTo(id.value());
    assertThat(added.eventType()).isEqualTo("ReactionAdded");
    assertThat(added.eventVersion()).isEqualTo(1);
    assertThat(added.routingKey()).isEqualTo("reaction.added");
    assertThat(added.payload())
        .containsOnlyKeys("messageId", "channelId", "userId", "emoji")
        .containsEntry("messageId", id.value().toString())
        .containsEntry("channelId", channel.value().toString())
        .containsEntry("userId", user.value().toString())
        .containsEntry("emoji", "👍");
    clearInvocations(writer);
    OutboxMessage removed = publish(new ReactionRemoved(id, channel, user, new Emoji("👍")));
    assertThat(removed.eventType()).isEqualTo("ReactionRemoved");
    assertThat(removed.routingKey()).isEqualTo("reaction.removed");
  }

  // Kịch bản: event chưa được map
  // Input: một DomainEvent lạ
  // Output: IllegalArgumentException
  @Test
  void rejectsUnmappedEvent() {
    assertThatThrownBy(() -> publisher.publish(new DomainEvent() {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Phát event rồi lấy OutboxMessage đã ghi
  private OutboxMessage publish(DomainEvent event) {
    publisher.publish(event);
    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer).append(captor.capture());
    return captor.getValue();
  }
}

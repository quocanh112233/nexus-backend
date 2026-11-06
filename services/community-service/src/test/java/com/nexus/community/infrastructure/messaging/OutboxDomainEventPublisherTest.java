package com.nexus.community.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.community.domain.category.CategoryCreated;
import com.nexus.community.domain.category.CategoryDeleted;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryUpdated;
import com.nexus.community.domain.channel.ChannelCreated;
import com.nexus.community.domain.channel.ChannelDeleted;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelUpdated;
import com.nexus.community.domain.member.MemberJoined;
import com.nexus.community.domain.member.MemberLeft;
import com.nexus.community.domain.server.ServerCreated;
import com.nexus.community.domain.server.ServerDeleted;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerUpdated;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Kiểm tra OutboxDomainEventPublisher ghim tên trường, routing key và aggregate type theo catalog
class OutboxDomainEventPublisherTest {

  private final OutboxWriter writer = mock(OutboxWriter.class);
  private final OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(writer);
  private final ServerId serverId = ServerId.newId();

  // Kịch bản: ServerCreated ghi vào outbox đúng aggregate, routing key và payload
  // Input: server tên "Nexus" của một owner
  // Output: aggregate Server, id server, server.created, payload serverId, name, ownerId
  @Test
  void mapsServerCreated() {
    UserId owner = UserId.newId();

    OutboxMessage m = publish(new ServerCreated(serverId, new ServerName("Nexus"), owner));

    assertThat(m.aggregateType()).isEqualTo("Server");
    assertThat(m.aggregateId()).isEqualTo(serverId.value());
    assertThat(m.eventType()).isEqualTo("ServerCreated");
    assertThat(m.eventVersion()).isEqualTo(1);
    assertThat(m.routingKey()).isEqualTo("server.created");
    assertThat(m.payload())
        .containsOnlyKeys("serverId", "name", "ownerId")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("name", "Nexus")
        .containsEntry("ownerId", owner.value().toString());
  }

  // Kịch bản: ServerUpdated và ServerDeleted ghi vào outbox đúng routing key và payload
  // Input: server đổi tên thành "Renamed" rồi server bị xóa
  // Output: server.updated có serverId, name; server.deleted chỉ có serverId; aggregate Server
  @Test
  void mapsServerUpdatedAndServerDeleted() {
    OutboxMessage updated = publish(new ServerUpdated(serverId, new ServerName("Renamed")));
    OutboxMessage deleted = publish(new ServerDeleted(serverId));

    assertThat(updated.aggregateType()).isEqualTo("Server");
    assertThat(updated.aggregateId()).isEqualTo(serverId.value());
    assertThat(updated.routingKey()).isEqualTo("server.updated");
    assertThat(updated.payload())
        .containsOnlyKeys("serverId", "name")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("name", "Renamed");
    assertThat(deleted.aggregateType()).isEqualTo("Server");
    assertThat(deleted.aggregateId()).isEqualTo(serverId.value());
    assertThat(deleted.routingKey()).isEqualTo("server.deleted");
    assertThat(deleted.payload())
        .containsOnlyKeys("serverId")
        .containsEntry("serverId", serverId.value().toString());
  }

  // Kịch bản: CategoryCreated ghi vào outbox đúng aggregate, routing key và payload
  // Input: category "Text Channels" vị trí 0
  // Output: aggregate Category, id category, category.created, payload 4 khóa đúng catalog
  @Test
  void mapsCategoryCreated() {
    CategoryId id = CategoryId.newId();

    OutboxMessage m =
        publish(
            new CategoryCreated(serverId, id, new CategoryName("Text Channels"), new Position(0)));

    assertThat(m.aggregateType()).isEqualTo("Category");
    assertThat(m.aggregateId()).isEqualTo(id.value());
    assertThat(m.routingKey()).isEqualTo("category.created");
    assertThat(m.payload())
        .containsOnlyKeys("serverId", "categoryId", "name", "position")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("categoryId", id.value().toString())
        .containsEntry("name", "Text Channels")
        .containsEntry("position", 0);
  }

  // Kịch bản: ChannelCreated ghi vào outbox, categoryId có thể null
  // Input: kênh general có category rồi kênh không category
  // Output: aggregate Channel, channel.created, payload có categoryId chuỗi hoặc null
  @Test
  void mapsChannelCreatedWithAndWithoutCategory() {
    ChannelId id = ChannelId.newId();
    CategoryId category = CategoryId.newId();

    OutboxMessage with =
        publish(
            new ChannelCreated(
                serverId, id, category, new ChannelName("general"), new Position(0)));
    OutboxMessage without =
        publish(
            new ChannelCreated(serverId, id, null, new ChannelName("general"), new Position(1)));

    assertThat(with.aggregateType()).isEqualTo("Channel");
    assertThat(with.aggregateId()).isEqualTo(id.value());
    assertThat(with.routingKey()).isEqualTo("channel.created");
    assertThat(with.payload())
        .containsOnlyKeys("serverId", "channelId", "categoryId", "name", "position")
        .containsEntry("channelId", id.value().toString())
        .containsEntry("categoryId", category.value().toString())
        .containsEntry("name", "general")
        .containsEntry("position", 0);
    assertThat(without.payload()).containsEntry("categoryId", null).containsEntry("position", 1);
  }

  // Kịch bản: CategoryUpdated và CategoryDeleted ghi vào outbox đúng routing key và payload
  // Input: category đổi thành "Voice" vị trí 2 rồi category bị xóa
  // Output: category.updated 4 khóa; category.deleted chỉ serverId, categoryId; aggregate Category
  @Test
  void mapsCategoryUpdatedAndCategoryDeleted() {
    CategoryId id = CategoryId.newId();

    OutboxMessage updated =
        publish(new CategoryUpdated(serverId, id, new CategoryName("Voice"), new Position(2)));
    OutboxMessage deleted = publish(new CategoryDeleted(serverId, id));

    assertThat(updated.aggregateType()).isEqualTo("Category");
    assertThat(updated.aggregateId()).isEqualTo(id.value());
    assertThat(updated.routingKey()).isEqualTo("category.updated");
    assertThat(updated.payload())
        .containsOnlyKeys("serverId", "categoryId", "name", "position")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("categoryId", id.value().toString())
        .containsEntry("name", "Voice")
        .containsEntry("position", 2);
    assertThat(deleted.aggregateType()).isEqualTo("Category");
    assertThat(deleted.aggregateId()).isEqualTo(id.value());
    assertThat(deleted.routingKey()).isEqualTo("category.deleted");
    assertThat(deleted.payload())
        .containsOnlyKeys("serverId", "categoryId")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("categoryId", id.value().toString());
  }

  // Kịch bản: ChannelUpdated và ChannelDeleted ghi vào outbox, categoryId và topic có thể null
  // Input: kênh không category, topic null rồi kênh có category, topic "hi", rồi kênh bị xóa
  // Output: channel.updated 6 khóa đúng catalog; channel.deleted chỉ serverId, channelId
  @Test
  void mapsChannelUpdatedAndChannelDeleted() {
    ChannelId id = ChannelId.newId();
    CategoryId category = CategoryId.newId();

    OutboxMessage bare =
        publish(
            new ChannelUpdated(
                serverId, id, null, new ChannelName("general"), null, new Position(1)));
    OutboxMessage full =
        publish(
            new ChannelUpdated(
                serverId, id, category, new ChannelName("general"), "hi", new Position(3)));
    OutboxMessage deleted = publish(new ChannelDeleted(serverId, id));

    assertThat(bare.aggregateType()).isEqualTo("Channel");
    assertThat(bare.aggregateId()).isEqualTo(id.value());
    assertThat(bare.routingKey()).isEqualTo("channel.updated");
    assertThat(bare.payload())
        .containsOnlyKeys("serverId", "channelId", "categoryId", "name", "topic", "position")
        .containsEntry("categoryId", null)
        .containsEntry("topic", null)
        .containsEntry("position", 1);
    assertThat(full.payload())
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("channelId", id.value().toString())
        .containsEntry("categoryId", category.value().toString())
        .containsEntry("name", "general")
        .containsEntry("topic", "hi")
        .containsEntry("position", 3);
    assertThat(deleted.aggregateType()).isEqualTo("Channel");
    assertThat(deleted.routingKey()).isEqualTo("channel.deleted");
    assertThat(deleted.payload())
        .containsOnlyKeys("serverId", "channelId")
        .containsEntry("channelId", id.value().toString());
  }

  // Kịch bản: MemberJoined ghi vào outbox với aggregateId là serverId
  // Input: user vào server lúc 2026-11-05T01:02:03.123456Z
  // Output: aggregate Member, id server, member.joined, payload serverId, userId, joinedAt ISO-8601
  @Test
  void mapsMemberJoinedWithServerAsAggregateId() {
    UserId user = UserId.newId();

    OutboxMessage m =
        publish(new MemberJoined(serverId, user, Instant.parse("2026-11-05T01:02:03.123456Z")));

    assertThat(m.aggregateType()).isEqualTo("Member");
    assertThat(m.aggregateId()).isEqualTo(serverId.value());
    assertThat(m.routingKey()).isEqualTo("member.joined");
    assertThat(m.payload())
        .containsOnlyKeys("serverId", "userId", "joinedAt")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("userId", user.value().toString())
        .containsEntry("joinedAt", "2026-11-05T01:02:03.123456Z");
  }

  // Kịch bản: MemberLeft ghi vào outbox với aggregateId là serverId
  // Input: user rời server
  // Output: aggregate Member, id server, member.left, payload chỉ serverId và userId
  @Test
  void mapsMemberLeftWithServerAsAggregateId() {
    UserId user = UserId.newId();

    OutboxMessage m = publish(new MemberLeft(serverId, user));

    assertThat(m.aggregateType()).isEqualTo("Member");
    assertThat(m.aggregateId()).isEqualTo(serverId.value());
    assertThat(m.routingKey()).isEqualTo("member.left");
    assertThat(m.payload())
        .containsOnlyKeys("serverId", "userId")
        .containsEntry("serverId", serverId.value().toString())
        .containsEntry("userId", user.value().toString());
  }

  // Kịch bản: event chưa được map bị từ chối rõ ràng
  // Input: một DomainEvent ẩn danh
  // Output: IllegalArgumentException và không ghi gì vào outbox
  @Test
  void unmappedEventThrows() {
    assertThatThrownBy(() -> publisher.publish(new DomainEvent() {}))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verifyNoInteractions(writer);
  }

  // Phát event rồi bắt OutboxMessage đã ghi
  private OutboxMessage publish(DomainEvent event) {
    org.mockito.Mockito.reset(writer);
    publisher.publish(event);
    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(writer).append(captor.capture());
    return captor.getValue();
  }
}

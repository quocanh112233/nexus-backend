package com.nexus.community.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.community.domain.ban.MemberBanned;
import com.nexus.community.domain.ban.MemberUnbanned;
import com.nexus.community.domain.category.CategoryCreated;
import com.nexus.community.domain.category.CategoryDeleted;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.category.CategoryName;
import com.nexus.community.domain.category.CategoryUpdated;
import com.nexus.community.domain.channel.ChannelCreated;
import com.nexus.community.domain.channel.ChannelDeleted;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.channel.ChannelPermissionsChanged;
import com.nexus.community.domain.channel.ChannelUpdated;
import com.nexus.community.domain.member.MemberJoined;
import com.nexus.community.domain.member.MemberKicked;
import com.nexus.community.domain.member.MemberLeft;
import com.nexus.community.domain.member.MemberRolesChanged;
import com.nexus.community.domain.server.Permission;
import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.RoleCreated;
import com.nexus.community.domain.server.RoleDeleted;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.RoleUpdated;
import com.nexus.community.domain.server.ServerCreated;
import com.nexus.community.domain.server.ServerDeleted;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.server.ServerUpdated;
import com.nexus.community.domain.shared.DomainEvent;
import com.nexus.community.domain.shared.Position;
import com.nexus.community.domain.shared.UserId;
import com.nexus.testsupport.ContractFixtures;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

// Kiểm tra payload community.events khớp fixture trong contracts/events
class OutboxEventContractTest {

  // Các event community: tên, routing key và event miền mẫu (trường nullable có giá trị)
  static Stream<Arguments> events() {
    ServerId server = ServerId.newId();
    UserId user = UserId.newId();
    UserId admin = UserId.newId();
    ChannelId channel = ChannelId.newId();
    CategoryId category = CategoryId.newId();
    RoleId role = RoleId.newId();
    PermissionSet perms = new PermissionSet(0).with(Permission.SEND_MESSAGES);
    return Stream.of(
        Arguments.of(
            "ServerCreated",
            "server.created",
            new ServerCreated(server, new ServerName("Nexus"), user)),
        Arguments.of(
            "ServerUpdated", "server.updated", new ServerUpdated(server, new ServerName("Nexus"))),
        Arguments.of("ServerDeleted", "server.deleted", new ServerDeleted(server)),
        Arguments.of(
            "CategoryCreated",
            "category.created",
            new CategoryCreated(server, category, new CategoryName("Text"), new Position(0))),
        Arguments.of(
            "CategoryUpdated",
            "category.updated",
            new CategoryUpdated(server, category, new CategoryName("Text"), new Position(0))),
        Arguments.of("CategoryDeleted", "category.deleted", new CategoryDeleted(server, category)),
        Arguments.of(
            "ChannelCreated",
            "channel.created",
            new ChannelCreated(
                server, channel, category, new ChannelName("general"), new Position(0))),
        Arguments.of(
            "ChannelUpdated",
            "channel.updated",
            new ChannelUpdated(
                server, channel, category, new ChannelName("general"), "hi", new Position(0))),
        Arguments.of("ChannelDeleted", "channel.deleted", new ChannelDeleted(server, channel)),
        Arguments.of(
            "ChannelPermissionsChanged",
            "channel.permissions-changed",
            new ChannelPermissionsChanged(server, channel)),
        Arguments.of(
            "MemberJoined", "member.joined", new MemberJoined(server, user, Instant.now())),
        Arguments.of("MemberLeft", "member.left", new MemberLeft(server, user)),
        Arguments.of("MemberKicked", "member.kicked", new MemberKicked(server, user, admin)),
        Arguments.of(
            "MemberBanned", "member.banned", new MemberBanned(server, user, admin, "spam", true)),
        Arguments.of("MemberUnbanned", "member.unbanned", new MemberUnbanned(server, user, admin)),
        Arguments.of(
            "MemberRolesChanged",
            "member.roles-changed",
            new MemberRolesChanged(server, user, List.of(role))),
        Arguments.of("RoleCreated", "role.created", new RoleCreated(server, role, "Mod", perms, 1)),
        Arguments.of("RoleUpdated", "role.updated", new RoleUpdated(server, role, "Mod", perms, 1)),
        Arguments.of("RoleDeleted", "role.deleted", new RoleDeleted(server, role)));
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

package com.nexus.community.infrastructure.messaging;

import com.nexus.common.messaging.OutboxMessage;
import com.nexus.common.messaging.OutboxWriter;
import com.nexus.community.application.port.DomainEventPublisher;
import com.nexus.community.domain.ban.MemberBanned;
import com.nexus.community.domain.ban.MemberUnbanned;
import com.nexus.community.domain.category.CategoryCreated;
import com.nexus.community.domain.category.CategoryDeleted;
import com.nexus.community.domain.category.CategoryUpdated;
import com.nexus.community.domain.channel.ChannelCreated;
import com.nexus.community.domain.channel.ChannelDeleted;
import com.nexus.community.domain.channel.ChannelPermissionsChanged;
import com.nexus.community.domain.channel.ChannelUpdated;
import com.nexus.community.domain.member.MemberJoined;
import com.nexus.community.domain.member.MemberKicked;
import com.nexus.community.domain.member.MemberLeft;
import com.nexus.community.domain.member.MemberRolesChanged;
import com.nexus.community.domain.server.RoleCreated;
import com.nexus.community.domain.server.RoleDeleted;
import com.nexus.community.domain.server.RoleUpdated;
import com.nexus.community.domain.server.ServerCreated;
import com.nexus.community.domain.server.ServerDeleted;
import com.nexus.community.domain.server.ServerUpdated;
import com.nexus.community.domain.shared.DomainEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

// Phát sự kiện miền bằng cách ghi vào outbox cùng transaction của use case
@Component
class OutboxDomainEventPublisher implements DomainEventPublisher {

  private final OutboxWriter outboxWriter;

  // Nhận OutboxWriter qua constructor
  OutboxDomainEventPublisher(OutboxWriter outboxWriter) {
    this.outboxWriter = outboxWriter;
  }

  // Đổi sự kiện miền thành OutboxMessage theo event-catalog rồi ghi, event chưa map thì ném lỗi
  @Override
  public void publish(DomainEvent event) {
    outboxWriter.append(
        switch (event) {
          case ServerCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("name", e.name().value());
            payload.put("ownerId", e.ownerId().value().toString());
            yield new OutboxMessage(
                "Server", e.serverId().value(), "ServerCreated", 1, "server.created", payload);
          }
          case ServerUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("name", e.name().value());
            yield new OutboxMessage(
                "Server", e.serverId().value(), "ServerUpdated", 1, "server.updated", payload);
          }
          case ServerDeleted e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            yield new OutboxMessage(
                "Server", e.serverId().value(), "ServerDeleted", 1, "server.deleted", payload);
          }
          case CategoryCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("categoryId", e.categoryId().value().toString());
            payload.put("name", e.name().value());
            payload.put("position", e.position().value());
            yield new OutboxMessage(
                "Category",
                e.categoryId().value(),
                "CategoryCreated",
                1,
                "category.created",
                payload);
          }
          case ChannelCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put(
                "categoryId", e.categoryId() == null ? null : e.categoryId().value().toString());
            payload.put("name", e.name().value());
            payload.put("position", e.position().value());
            yield new OutboxMessage(
                "Channel", e.channelId().value(), "ChannelCreated", 1, "channel.created", payload);
          }
          case CategoryUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("categoryId", e.categoryId().value().toString());
            payload.put("name", e.name().value());
            payload.put("position", e.position().value());
            yield new OutboxMessage(
                "Category",
                e.categoryId().value(),
                "CategoryUpdated",
                1,
                "category.updated",
                payload);
          }
          case CategoryDeleted e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("categoryId", e.categoryId().value().toString());
            yield new OutboxMessage(
                "Category",
                e.categoryId().value(),
                "CategoryDeleted",
                1,
                "category.deleted",
                payload);
          }
          case ChannelUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            payload.put(
                "categoryId", e.categoryId() == null ? null : e.categoryId().value().toString());
            payload.put("name", e.name().value());
            payload.put("topic", e.topic());
            payload.put("position", e.position().value());
            yield new OutboxMessage(
                "Channel", e.channelId().value(), "ChannelUpdated", 1, "channel.updated", payload);
          }
          case ChannelDeleted e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            yield new OutboxMessage(
                "Channel", e.channelId().value(), "ChannelDeleted", 1, "channel.deleted", payload);
          }
          case ChannelPermissionsChanged e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("channelId", e.channelId().value().toString());
            yield new OutboxMessage(
                "Channel",
                e.channelId().value(),
                "ChannelPermissionsChanged",
                1,
                "channel.permissions-changed",
                payload);
          }
          case MemberJoined e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            payload.put("joinedAt", e.joinedAt().toString());
            yield new OutboxMessage(
                "Member", e.serverId().value(), "MemberJoined", 1, "member.joined", payload);
          }
          case MemberLeft e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            yield new OutboxMessage(
                "Member", e.serverId().value(), "MemberLeft", 1, "member.left", payload);
          }
          case MemberKicked e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            payload.put("kickedBy", e.kickedBy().value().toString());
            yield new OutboxMessage(
                "Member", e.serverId().value(), "MemberKicked", 1, "member.kicked", payload);
          }
          case MemberBanned e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            payload.put("bannedBy", e.bannedBy().value().toString());
            payload.put("reason", e.reason());
            payload.put("wasMember", e.wasMember());
            yield new OutboxMessage(
                "Ban", e.serverId().value(), "MemberBanned", 1, "member.banned", payload);
          }
          case MemberUnbanned e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            payload.put("unbannedBy", e.unbannedBy().value().toString());
            yield new OutboxMessage(
                "Ban", e.serverId().value(), "MemberUnbanned", 1, "member.unbanned", payload);
          }
          case RoleCreated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("roleId", e.roleId().value().toString());
            payload.put("name", e.name());
            payload.put("permissions", String.valueOf(e.permissions().bits()));
            payload.put("position", e.position());
            yield new OutboxMessage(
                "Role", e.roleId().value(), "RoleCreated", 1, "role.created", payload);
          }
          case RoleUpdated e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("roleId", e.roleId().value().toString());
            payload.put("name", e.name());
            payload.put("permissions", String.valueOf(e.permissions().bits()));
            payload.put("position", e.position());
            yield new OutboxMessage(
                "Role", e.roleId().value(), "RoleUpdated", 1, "role.updated", payload);
          }
          case RoleDeleted e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("roleId", e.roleId().value().toString());
            yield new OutboxMessage(
                "Role", e.roleId().value(), "RoleDeleted", 1, "role.deleted", payload);
          }
          case MemberRolesChanged e -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverId", e.serverId().value().toString());
            payload.put("userId", e.userId().value().toString());
            payload.put("roleIds", e.roleIds().stream().map(r -> r.value().toString()).toList());
            yield new OutboxMessage(
                "Member",
                e.serverId().value(),
                "MemberRolesChanged",
                1,
                "member.roles-changed",
                payload);
          }
          default -> throw new IllegalArgumentException("unmapped event " + event.getClass());
        });
  }
}

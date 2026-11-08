package com.nexus.messaging.application.channel;

import com.nexus.common.messaging.EventEnvelope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Event của community mà messaging quan tâm, đã tách khỏi envelope
public record CommunityEvent(UUID eventId, String eventType, UUID serverId, UUID channelId) {

  private static final Set<String> NEEDS_CHANNEL = Set.of("ChannelCreated", "ChannelDeleted");
  private static final Set<String> SUPPORTED =
      Set.of(
          "ChannelCreated",
          "ChannelDeleted",
          "ServerDeleted",
          "ChannelPermissionsChanged",
          "MemberLeft",
          "MemberKicked",
          "MemberBanned",
          "MemberRolesChanged",
          "RoleUpdated",
          "RoleDeleted");

  // Loại lạ bỏ qua payload; loại hỗ trợ bắt buộc serverId, kênh tạo/xóa thêm channelId
  public static CommunityEvent from(EventEnvelope envelope) {
    if (!SUPPORTED.contains(envelope.eventType())) {
      return new CommunityEvent(envelope.eventId(), envelope.eventType(), null, null);
    }
    Map<String, Object> payload = envelope.payload();
    UUID channelId =
        NEEDS_CHANNEL.contains(envelope.eventType()) ? uuid(payload, "channelId") : null;
    return new CommunityEvent(
        envelope.eventId(), envelope.eventType(), uuid(payload, "serverId"), channelId);
  }

  // Lấy trường UUID bắt buộc của payload
  private static UUID uuid(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) {
      throw new IllegalArgumentException("payload thiếu " + key);
    }
    return UUID.fromString(value.toString());
  }
}

package com.nexus.notification.application.community;

import com.nexus.common.messaging.EventEnvelope;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Event của community mà notification quan tâm, đã tách khỏi envelope
public record CommunityEvent(
    UUID eventId, String eventType, UUID serverId, UUID channelId, UUID userId, Instant joinedAt) {

  private static final Set<String> NEEDS_CHANNEL = Set.of("ChannelCreated", "ChannelDeleted");
  private static final Set<String> NEEDS_USER =
      Set.of("MemberJoined", "MemberLeft", "MemberKicked", "MemberBanned");
  private static final Set<String> SUPPORTED =
      Set.of(
          "ChannelCreated",
          "ChannelDeleted",
          "ServerDeleted",
          "MemberJoined",
          "MemberLeft",
          "MemberKicked",
          "MemberBanned");

  // Loại lạ bỏ qua payload; loại hỗ trợ bắt buộc serverId kèm channelId, userId, joinedAt tùy loại
  public static CommunityEvent from(EventEnvelope envelope) {
    String type = envelope.eventType();
    if (!SUPPORTED.contains(type)) {
      return new CommunityEvent(envelope.eventId(), type, null, null, null, null);
    }
    Map<String, Object> payload = envelope.payload();
    return new CommunityEvent(
        envelope.eventId(),
        type,
        uuid(payload, "serverId"),
        NEEDS_CHANNEL.contains(type) ? uuid(payload, "channelId") : null,
        NEEDS_USER.contains(type) ? uuid(payload, "userId") : null,
        type.equals("MemberJoined") ? instant(payload, "joinedAt") : null);
  }

  // Lấy trường UUID bắt buộc của payload
  private static UUID uuid(Map<String, Object> payload, String key) {
    return UUID.fromString(required(payload, key));
  }

  // Lấy trường thời điểm ISO-8601 bắt buộc của payload
  private static Instant instant(Map<String, Object> payload, String key) {
    try {
      return Instant.parse(required(payload, key));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("payload sai định dạng " + key, e);
    }
  }

  // Lấy chuỗi của trường bắt buộc, thiếu thì ném IllegalArgumentException
  private static String required(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) {
      throw new IllegalArgumentException("payload thiếu " + key);
    }
    return value.toString();
  }
}

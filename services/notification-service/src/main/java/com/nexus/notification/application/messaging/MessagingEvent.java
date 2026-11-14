package com.nexus.notification.application.messaging;

import com.nexus.common.messaging.EventEnvelope;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Event của messaging mà notification quan tâm, đã tách khỏi envelope; serverId null với DM
public record MessagingEvent(
    UUID eventId,
    String eventType,
    UUID messageId,
    UUID channelId,
    UUID serverId,
    UUID authorId,
    List<UUID> mentionUserIds,
    Instant createdAt,
    List<UUID> participantIds) {

  // MessageCreated cần id tin, kênh, tác giả, createdAt; DmChannelCreated cần đúng 2 participant
  public static MessagingEvent from(EventEnvelope envelope) {
    String type = envelope.eventType();
    Map<String, Object> payload = envelope.payload();
    return switch (type) {
      case "MessageCreated" ->
          new MessagingEvent(
              envelope.eventId(),
              type,
              uuid(payload, "messageId"),
              uuid(payload, "channelId"),
              payload.get("serverId") == null ? null : uuid(payload, "serverId"),
              uuid(payload, "authorId"),
              uuids(payload, "mentionUserIds"),
              instant(payload, "createdAt"),
              List.of());
      case "DmChannelCreated" -> {
        List<UUID> participants = uuids(payload, "participantIds");
        if (participants.size() != 2) {
          throw new IllegalArgumentException("participantIds cần đúng 2 phần tử");
        }
        yield new MessagingEvent(
            envelope.eventId(),
            type,
            null,
            uuid(payload, "channelId"),
            null,
            null,
            List.of(),
            null,
            participants);
      }
      default ->
          new MessagingEvent(
              envelope.eventId(), type, null, null, null, null, List.of(), null, List.of());
    };
  }

  // Lấy trường UUID bắt buộc của payload
  private static UUID uuid(Map<String, Object> payload, String key) {
    return UUID.fromString(required(payload, key));
  }

  // Lấy danh sách UUID của payload, thiếu hoặc null thì rỗng
  private static List<UUID> uuids(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("payload sai định dạng " + key);
    }
    return list.stream().map(o -> UUID.fromString(String.valueOf(o))).toList();
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

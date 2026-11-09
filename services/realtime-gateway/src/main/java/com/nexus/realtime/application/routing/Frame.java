package com.nexus.realtime.application.routing;

import com.nexus.common.messaging.EventEnvelope;
import java.util.Map;

// Frame JSON gửi cho client: type, eventId, occurredAt (ISO) và data là payload của event
public record Frame(String type, String eventId, String occurredAt, Map<String, Object> data) {

  // Dựng frame từ envelope với data cho trước, data null thì dùng nguyên payload
  public static Frame of(EventEnvelope envelope, Map<String, Object> data) {
    return new Frame(
        envelope.eventType(),
        envelope.eventId().toString(),
        envelope.occurredAt() == null ? null : envelope.occurredAt().toString(),
        data == null ? envelope.payload() : data);
  }
}

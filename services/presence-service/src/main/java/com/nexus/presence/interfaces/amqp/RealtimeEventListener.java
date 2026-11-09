package com.nexus.presence.interfaces.amqp;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.presence.application.presence.SessionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Nhận session.* và gateway.heartbeat từ queue presence.realtime.events
@Component
public class RealtimeEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(RealtimeEventListener.class);
  private static final Set<String> SUPPORTED =
      Set.of("SessionConnected", "SessionDisconnected", "GatewayHeartbeat");

  private final EventEnvelopeReader reader;
  private final SessionService service;

  // Nhận bộ đọc envelope và service session
  public RealtimeEventListener(EventEnvelopeReader reader, SessionService service) {
    this.reader = reader;
    this.service = service;
  }

  // Loại lạ ack bỏ qua trước khi kiểm payload; payload sai thì reject không requeue vào DLQ ngay
  @RabbitListener(queues = "presence.realtime.events")
  public void on(Message message) {
    EventEnvelope envelope = reader.read(message);
    String type = envelope.eventType();
    if (!SUPPORTED.contains(type)) {
      return;
    }
    Map<String, Object> p = envelope.payload();
    try {
      switch (type) {
        case "SessionConnected" ->
            service.connect(
                text(p, "sessionId"),
                UUID.fromString(text(p, "userId")),
                text(p, "instanceId"),
                Instant.parse(text(p, "connectedAt")));
        case "SessionDisconnected" -> service.disconnect(text(p, "sessionId"));
        default ->
            service.heartbeat(text(p, "instanceId"), sessions(p), Instant.parse(text(p, "sentAt")));
      }
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new AmqpRejectAndDontRequeueException("Payload không hợp lệ", e);
    }
  }

  // Ghép sessionIds với userIds cùng vị trí; thiếu userIds (gateway cũ) thì userId là null
  private static Map<String, UUID> sessions(Map<String, Object> payload) {
    List<?> ids = list(payload, "sessionIds");
    Object users = payload.get("userIds");
    if (ids == null) {
      throw new IllegalArgumentException("payload thiếu sessionIds");
    }
    List<?> userIds = users instanceof List<?> l && l.size() == ids.size() ? l : null;
    if (userIds == null && !ids.isEmpty()) {
      LOG.warn("Heartbeat có userIds thiếu hoặc lệch sessionIds, chỉ ngắt session vắng");
    }
    Map<String, UUID> out = new LinkedHashMap<>();
    for (int i = 0; i < ids.size(); i++) {
      out.put(String.valueOf(ids.get(i)), userIds == null ? null : uuid(userIds.get(i)));
    }
    return out;
  }

  // Lấy trường danh sách của payload, thiếu hoặc sai kiểu thì null
  private static List<?> list(Map<String, Object> payload, String key) {
    return payload.get(key) instanceof List<?> l ? l : null;
  }

  // Đổi phần tử userIds sang UUID, rỗng thì null
  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  // Lấy trường chuỗi bắt buộc không rỗng của payload
  private static String text(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException("payload thiếu " + key);
    }
    return value.toString();
  }
}

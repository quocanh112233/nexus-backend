package com.nexus.presence.interfaces.amqp;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.presence.application.relation.ApplyRelationEventUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Nhận event bạn bè từ queue presence.messaging.events và chuyển cho use case
@Component
public class FriendEventListener {

  private static final Map<String, String[]> FIELDS =
      Map.of(
          "FriendRequestAccepted", new String[] {"requesterId", "addresseeId"},
          "FriendRemoved", new String[] {"userId", "friendId"});

  private final EventEnvelopeReader reader;
  private final ApplyRelationEventUseCase useCase;

  // Nhận bộ đọc envelope và use case
  public FriendEventListener(EventEnvelopeReader reader, ApplyRelationEventUseCase useCase) {
    this.reader = reader;
    this.useCase = useCase;
  }

  // Loại lạ ack bỏ qua trước khi kiểm payload; payload sai thì reject không requeue vào DLQ ngay
  @RabbitListener(queues = "presence.messaging.events")
  public void on(Message message) {
    EventEnvelope envelope = reader.read(message);
    String[] fields = FIELDS.get(envelope.eventType());
    if (fields == null) {
      return;
    }
    UUID a;
    UUID b;
    try {
      a = uuid(envelope.payload(), fields[0]);
      b = uuid(envelope.payload(), fields[1]);
    } catch (IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("Payload không hợp lệ", e);
    }
    useCase.executeFriend(envelope.eventType(), a, b);
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

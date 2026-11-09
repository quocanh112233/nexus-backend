package com.nexus.presence.interfaces.amqp;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.presence.application.relation.ApplyRelationEventUseCase;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Nhận event từ queue presence.community.events và chuyển cho use case
@Component
public class CommunityEventListener {

  private static final Set<String> SUPPORTED =
      Set.of("MemberJoined", "MemberLeft", "MemberKicked", "MemberBanned", "ServerDeleted");

  private final EventEnvelopeReader reader;
  private final ApplyRelationEventUseCase useCase;

  // Nhận bộ đọc envelope và use case
  public CommunityEventListener(EventEnvelopeReader reader, ApplyRelationEventUseCase useCase) {
    this.reader = reader;
    this.useCase = useCase;
  }

  // Loại lạ ack bỏ qua trước khi kiểm payload; payload sai thì reject không requeue vào DLQ ngay
  @RabbitListener(queues = "presence.community.events")
  public void on(Message message) {
    EventEnvelope envelope = reader.read(message);
    String type = envelope.eventType();
    if (!SUPPORTED.contains(type)) {
      return;
    }
    UUID serverId;
    UUID userId = null;
    try {
      serverId = uuid(envelope.payload(), "serverId");
      if (!type.equals("ServerDeleted")) {
        userId = uuid(envelope.payload(), "userId");
      }
    } catch (IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("Payload không hợp lệ", e);
    }
    useCase.execute(type, serverId, userId);
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

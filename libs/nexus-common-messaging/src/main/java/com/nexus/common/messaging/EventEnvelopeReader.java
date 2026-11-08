package com.nexus.common.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// Đọc body message thành EventEnvelope, body hỏng thì reject không requeue để vào DLQ ngay
public class EventEnvelopeReader {

  private final JsonMapper mapper;

  // Dựng reader dùng JsonMapper của service
  public EventEnvelopeReader(JsonMapper mapper) {
    this.mapper = mapper;
  }

  // Parse JSON và kiểm tra eventId, eventType, payload
  public EventEnvelope read(Message message) {
    EventEnvelope envelope;
    try {
      envelope = mapper.readValue(message.getBody(), EventEnvelope.class);
    } catch (JacksonException e) {
      throw new AmqpRejectAndDontRequeueException("Envelope không đọc được", e);
    }
    if (envelope == null
        || envelope.eventId() == null
        || envelope.eventType() == null
        || envelope.payload() == null) {
      throw new AmqpRejectAndDontRequeueException("Envelope thiếu eventId/eventType/payload");
    }
    return envelope;
  }
}

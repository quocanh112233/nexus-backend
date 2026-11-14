package com.nexus.notification.interfaces.messaging;

import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.notification.application.messaging.ApplyMessagingEventUseCase;
import com.nexus.notification.application.messaging.MessagingEvent;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Nhận event từ queue notification.messaging.events và chuyển cho use case
@Component
public class MessagingEventListener {

  private final EventEnvelopeReader reader;
  private final ApplyMessagingEventUseCase useCase;

  // Nhận bộ đọc envelope và use case
  public MessagingEventListener(EventEnvelopeReader reader, ApplyMessagingEventUseCase useCase) {
    this.reader = reader;
    this.useCase = useCase;
  }

  // Đọc envelope, tách event rồi áp dụng, lỗi parse thì reject không requeue để vào DLQ ngay
  @RabbitListener(queues = "notification.messaging.events")
  public void on(Message message) {
    MessagingEvent event;
    try {
      event = MessagingEvent.from(reader.read(message));
    } catch (IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("Payload không hợp lệ", e);
    }
    useCase.execute(event);
  }
}

package com.nexus.notification.interfaces.messaging;

import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.notification.application.community.ApplyCommunityEventUseCase;
import com.nexus.notification.application.community.CommunityEvent;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// Nhận event từ queue notification.community.events và chuyển cho use case
@Component
public class CommunityEventListener {

  private final EventEnvelopeReader reader;
  private final ApplyCommunityEventUseCase useCase;

  // Nhận bộ đọc envelope và use case
  public CommunityEventListener(EventEnvelopeReader reader, ApplyCommunityEventUseCase useCase) {
    this.reader = reader;
    this.useCase = useCase;
  }

  // Đọc envelope rồi áp dụng, lỗi parse thì vào DLQ ngay; BackfillRunner start sau khi nạp xong
  @RabbitListener(
      id = "community-events",
      queues = "notification.community.events",
      autoStartup = "false")
  public void on(Message message) {
    CommunityEvent event;
    try {
      event = CommunityEvent.from(reader.read(message));
    } catch (IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("Payload không hợp lệ", e);
    }
    useCase.execute(event);
  }
}

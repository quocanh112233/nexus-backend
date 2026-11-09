package com.nexus.realtime.infrastructure.amqp;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import com.nexus.realtime.application.routing.EventRouter;
import com.nexus.realtime.application.routing.Frame;
import com.nexus.realtime.application.routing.Route;
import com.nexus.realtime.application.subscription.RevocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

// Nghe queue riêng của instance, đổi event thành frame và đẩy vào session cục bộ qua broker STOMP
@Component
public class BrokerEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(BrokerEventListener.class);

  private final EventEnvelopeReader reader;
  private final SimpMessagingTemplate template;
  private final RevocationService revocation;

  // Nhận reader envelope, template gửi STOMP và dịch vụ thu hồi subscription
  public BrokerEventListener(
      EventEnvelopeReader reader, SimpMessagingTemplate template, RevocationService revocation) {
    this.reader = reader;
    this.template = template;
    this.revocation = revocation;
  }

  // Thu hồi subscription trước khi đẩy frame; lỗi nào cũng log rồi ack vì event chỉ là best-effort
  @RabbitListener(queues = "#{@instanceQueue.name}")
  void onMessage(Message message) {
    try {
      EventEnvelope envelope = reader.read(message);
      revocation.onEvent(envelope);
      for (Route route : EventRouter.route(envelope)) {
        Frame frame = Frame.of(envelope, route.data());
        if (route.userId() == null) {
          template.convertAndSend(route.destination(), frame);
        } else {
          template.convertAndSendToUser(route.userId().toString(), route.destination(), frame);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("Bỏ qua event realtime lỗi: {}", e.toString());
    }
  }
}

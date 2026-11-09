package com.nexus.realtime.infrastructure.stomp;

import com.nexus.realtime.application.routing.Frame;
import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRevoker;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

// Gỡ subscription khỏi simple broker bằng UNSUBSCRIBE giả rồi báo user qua /user/queue/events
@Component
public class StompSubscriptionRevoker implements SubscriptionRevoker {

  private final MessageChannel clientInboundChannel;
  private final SimpMessagingTemplate template;

  // Nhận kênh nhận từ client và template gửi STOMP
  public StompSubscriptionRevoker(
      @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel,
      SimpMessagingTemplate template) {
    this.clientInboundChannel = clientInboundChannel;
    this.template = template;
  }

  // UNSUBSCRIBE giả dọn broker (bất đồng bộ, outbound đã chặn frame) rồi báo SubscriptionRevoked
  // kèm lý do
  @Override
  public void revoke(SessionSubscription s, String reason) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
    accessor.setSessionId(s.sessionId());
    accessor.setSubscriptionId(s.subscriptionId());
    accessor.setLeaveMutable(true);
    clientInboundChannel.send(
        MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
    template.convertAndSendToUser(
        s.userId().toString(),
        "/queue/events",
        new Frame(
            "SubscriptionRevoked",
            UUID.randomUUID().toString(),
            Instant.now().toString(),
            Map.of("destination", s.destination(), "reason", reason)));
  }
}

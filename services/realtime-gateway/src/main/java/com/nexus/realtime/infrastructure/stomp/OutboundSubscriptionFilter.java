package com.nexus.realtime.infrastructure.stomp;

import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

// Chặn frame MESSAGE gửi ra client nếu (session, subscription) không còn trong sổ
@Component
public class OutboundSubscriptionFilter implements ChannelInterceptor {

  private final SubscriptionRegistry registry;

  // Nhận sổ subscription, nguồn sự thật duy nhất
  public OutboundSubscriptionFilter(SubscriptionRegistry registry) {
    this.registry = registry;
  }

  // Trả null (bỏ frame) khi MESSAGE thiếu hoặc không giao được (mất, tạm dừng), loại khác đi qua
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
      return message;
    }
    String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
    String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
    boolean live =
        sessionId != null
            && subscriptionId != null
            && registry.isDeliverable(sessionId, subscriptionId);
    return live ? message : null;
  }
}

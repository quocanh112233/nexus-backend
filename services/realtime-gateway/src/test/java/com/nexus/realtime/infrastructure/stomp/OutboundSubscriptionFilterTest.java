package com.nexus.realtime.infrastructure.stomp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

// Kiểm tra bộ lọc outbound chỉ cho MESSAGE của subscription còn trong sổ đi qua
class OutboundSubscriptionFilterTest {

  private final SubscriptionRegistry registry = new SubscriptionRegistry();
  private final OutboundSubscriptionFilter filter = new OutboundSubscriptionFilter(registry);

  // Dựng frame MESSAGE ra client của (session, subscription)
  private static Message<byte[]> message(String sessionId, String subscriptionId) {
    SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    accessor.setSessionId(sessionId);
    accessor.setSubscriptionId(subscriptionId);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  // Kịch bản: MESSAGE tới subscription còn trong sổ, và tới subscription đã bị xóa
  // Input: đăng ký (s1, sub-0), gửi MESSAGE cho (s1, sub-0) rồi xóa nó và gửi lại
  // Output: lần đầu đi qua nguyên vẹn, lần sau bị bỏ (null)
  @Test
  void messageDroppedOnceSubscriptionLeavesRegistry() {
    registry.add(new SessionSubscription("s1", UUID.randomUUID(), "sub-0", "/topic/x", null));
    Message<byte[]> live = message("s1", "sub-0");
    assertThat(filter.preSend(live, null)).isSameAs(live);

    registry.remove("s1", "sub-0");

    assertThat(filter.preSend(live, null)).isNull();
  }

  // Kịch bản: MESSAGE thiếu header subscription, và frame không phải MESSAGE
  // Input: MESSAGE không có subscriptionId, CONNECTED và heartbeat của STOMP
  // Output: MESSAGE bị bỏ (fail-closed), hai loại còn lại đi qua
  @Test
  void missingSubscriptionDroppedButControlFramesPass() {
    assertThat(filter.preSend(message("s1", null), null)).isNull();
    Message<byte[]> connected =
        MessageBuilder.createMessage(
            new byte[0], StompHeaderAccessor.create(StompCommand.CONNECTED).getMessageHeaders());
    Message<byte[]> heartbeat =
        MessageBuilder.createMessage(
            new byte[0],
            SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT).getMessageHeaders());

    assertThat(filter.preSend(connected, null)).isSameAs(connected);
    assertThat(filter.preSend(heartbeat, null)).isSameAs(heartbeat);
  }
}

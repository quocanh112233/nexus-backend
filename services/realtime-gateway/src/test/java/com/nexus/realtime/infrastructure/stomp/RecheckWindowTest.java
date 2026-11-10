package com.nexus.realtime.infrastructure.stomp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.realtime.application.subscription.AccessChecker;
import com.nexus.realtime.application.subscription.ChannelScope;
import com.nexus.realtime.application.subscription.RevocationService;
import com.nexus.realtime.application.subscription.SessionSubscription;
import com.nexus.realtime.application.subscription.SubscriptionRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

// Kiểm tra trong lúc re-check đang chờ RPC, frame của cả lô bị chặn bởi bộ lọc outbound thật
class RecheckWindowTest {

  private final UUID server = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final SubscriptionRegistry registry = new SubscriptionRegistry();
  private final OutboundSubscriptionFilter filter = new OutboundSubscriptionFilter(registry);
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  // Dựng frame MESSAGE ra client của (session, subscription)
  private static Message<byte[]> message(String sessionId, String subscriptionId) {
    SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    accessor.setSessionId(sessionId);
    accessor.setSubscriptionId(subscriptionId);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  // Kịch bản: RPC kiểm quyền đầu tiên đang bị chặn, lô có 3 subscription kênh
  // Input: MemberRolesChanged, checker chờ latch, MESSAGE tới cả 3 sub, rồi thả latch cho phép
  // Output: khi chờ cả 3 bị bỏ frame, sau khi thả cả 3 giao lại được
  @Test
  void wholeBatchDroppedWhileRpcPendingThenResumed() throws Exception {
    List<String> subs = List.of("a", "b", "c");
    subs.forEach(
        id ->
            registry.add(
                new SessionSubscription(
                    "s1", user, id, "/topic/channels/" + UUID.randomUUID(), server)));
    RevocationService service =
        new RevocationService(
            registry,
            new AccessChecker() {
              // Chặn mọi lần check tới khi thả latch rồi cho phép
              @Override
              public Optional<ChannelScope> canViewChannel(UUID userId, UUID channelId) {
                entered.countDown();
                await();
                return Optional.of(new ChannelScope(server));
              }

              // Không dùng trong kịch bản này
              @Override
              public boolean isServerMember(UUID userId, UUID serverId) {
                return true;
              }
            },
            (s, reason) -> {});
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "MemberRolesChanged",
            1,
            Instant.now(),
            "test",
            "Agg",
            UUID.randomUUID(),
            Map.of("serverId", server.toString(), "userId", user.toString()));
    Thread t = new Thread(() -> service.onEvent(event));
    t.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    for (String id : subs) {
      assertThat(registry.isDeliverable("s1", id)).isFalse();
      assertThat(filter.preSend(message("s1", id), null)).isNull();
    }
    release.countDown();
    t.join(2000);

    for (String id : subs) {
      Message<byte[]> m = message("s1", id);
      assertThat(filter.preSend(m, null)).isSameAs(m);
    }
  }

  // Chờ latch thả, ngắt thì coi như lỗi hạ tầng
  private void await() {
    try {
      release.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

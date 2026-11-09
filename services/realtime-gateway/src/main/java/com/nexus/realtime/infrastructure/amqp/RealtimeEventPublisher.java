package com.nexus.realtime.infrastructure.amqp;

import com.github.f4b6a3.uuid.UuidCreator;
import com.nexus.common.messaging.EventEnvelope;
import com.nexus.realtime.application.typing.TypingPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// Phát thẳng event của gateway lên realtime.events, lỗi chỉ log để không đụng tới kết nối client
@Component
public class RealtimeEventPublisher implements TypingPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(RealtimeEventPublisher.class);
  private static final String EXCHANGE = "realtime.events";

  private final RabbitTemplate rabbit;
  private final JsonMapper mapper;
  private final Clock clock;

  // Nhận template AMQP, mapper JSON và đồng hồ; event không route được chỉ log debug
  public RealtimeEventPublisher(RabbitTemplate rabbit, JsonMapper mapper, Clock clock) {
    rabbit.setReturnsCallback(r -> LOG.debug("Event {} chưa có queue nhận", r.getRoutingKey()));
    this.rabbit = rabbit;
    this.mapper = mapper;
    this.clock = clock;
  }

  // Phát SessionConnected cho Presence
  public void publishSessionConnected(
      String sessionId, UUID userId, String instanceId, Instant connectedAt) {
    publish(
        "session.connected",
        "SessionConnected",
        "Session",
        sessionId,
        Map.of(
            "userId",
            userId.toString(),
            "sessionId",
            sessionId,
            "instanceId",
            instanceId,
            "connectedAt",
            connectedAt.toString()),
        false);
  }

  // Phát SessionDisconnected cho Presence, trả false nếu gửi lỗi
  public boolean publishSessionDisconnected(String sessionId, UUID userId, String instanceId) {
    return publish(
        "session.disconnected",
        "SessionDisconnected",
        "Session",
        sessionId,
        Map.of("userId", userId.toString(), "sessionId", sessionId, "instanceId", instanceId),
        false);
  }

  // Phát GatewayHeartbeat kèm session đang mở; userIds cùng thứ tự để Presence nối session bị mất
  public void publishHeartbeat(String instanceId, Map<String, UUID> sessions, Instant sentAt) {
    List<String> sessionIds = List.copyOf(sessions.keySet());
    publish(
        "gateway.heartbeat",
        "GatewayHeartbeat",
        "Gateway",
        instanceId,
        Map.of(
            "instanceId",
            instanceId,
            "sessionIds",
            sessionIds,
            "userIds",
            sessionIds.stream().map(s -> sessions.get(s).toString()).toList(),
            "sentAt",
            sentAt.toString()),
        false);
  }

  // Phát TypingStarted không bền, hết hạn sau 5 s nếu không ai nhận
  @Override
  public void publishTyping(UUID channelId, UUID userId, Instant startedAt) {
    publish(
        "typing.started",
        "TypingStarted",
        "Channel",
        channelId.toString(),
        Map.of(
            "channelId", channelId.toString(),
            "userId", userId.toString(),
            "startedAt", startedAt.toString()),
        true);
  }

  // Gửi không chờ confirm (ponytail: đồng bộ, broker treo chặn thread STOMP, nâng lên executor)
  private boolean publish(
      String routingKey,
      String eventType,
      String aggregateType,
      String aggregateKey,
      Map<String, Object> payload,
      boolean ephemeral) {
    try {
      UUID eventId = UuidCreator.getTimeOrderedEpoch();
      var envelope =
          new EventEnvelope(
              eventId,
              eventType,
              1,
              clock.instant(),
              "realtime-gateway",
              aggregateType,
              UUID.nameUUIDFromBytes(aggregateKey.getBytes(StandardCharsets.UTF_8)),
              payload);
      var message =
          MessageBuilder.withBody(mapper.writeValueAsBytes(envelope))
              .setMessageId(eventId.toString())
              .setContentType("application/json")
              .setDeliveryMode(
                  ephemeral ? MessageDeliveryMode.NON_PERSISTENT : MessageDeliveryMode.PERSISTENT);
      if (ephemeral) {
        message.setExpiration("5000");
      }
      rabbit.send(EXCHANGE, routingKey, message.build());
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Phát {} lỗi, bỏ qua: {}", eventType, e.toString());
      return false;
    }
  }
}

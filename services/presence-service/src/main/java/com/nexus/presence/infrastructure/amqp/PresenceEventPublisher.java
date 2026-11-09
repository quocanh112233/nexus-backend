package com.nexus.presence.infrastructure.amqp;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.presence.application.presence.PresenceChange;
import com.nexus.presence.application.presence.PresenceChangePublisher;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// Phát thẳng PresenceChanged lên presence.events, không outbox; lỗi gửi chỉ log để không hỏng xử lý
@Component
public class PresenceEventPublisher implements PresenceChangePublisher {

  private static final Logger LOG = LoggerFactory.getLogger(PresenceEventPublisher.class);

  private final RabbitTemplate rabbit;
  private final JsonMapper mapper;
  private final Clock clock;

  // Nhận template AMQP, mapper JSON và đồng hồ; event chưa có queue nhận chỉ log debug
  public PresenceEventPublisher(RabbitTemplate rabbit, JsonMapper mapper, Clock clock) {
    rabbit.setReturnsCallback(r -> LOG.debug("Event {} chưa có queue nhận", r.getRoutingKey()));
    this.rabbit = rabbit;
    this.mapper = mapper;
    this.clock = clock;
  }

  // Gửi không chờ confirm theo payload của event-catalog §5, lỗi thì log warn
  @Override
  public void publish(PresenceChange change) {
    try {
      UUID eventId = UUID.randomUUID();
      var envelope =
          new EventEnvelope(
              eventId,
              "PresenceChanged",
              1,
              clock.instant(),
              "presence-service",
              "User",
              change.userId(),
              Map.of(
                  "userId", change.userId().toString(),
                  "status", change.status(),
                  "changedAt", change.changedAt().toString(),
                  "serverIds", change.serverIds().stream().map(UUID::toString).toList(),
                  "friendIds", change.friendIds().stream().map(UUID::toString).toList()));
      rabbit.send(
          "presence.events",
          "presence.changed",
          MessageBuilder.withBody(mapper.writeValueAsBytes(envelope))
              .setMessageId(eventId.toString())
              .setContentType("application/json")
              .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
              .build());
    } catch (RuntimeException e) {
      LOG.warn("Phát PresenceChanged của {} lỗi, bỏ qua: {}", change.userId(), e.toString());
    }
  }
}

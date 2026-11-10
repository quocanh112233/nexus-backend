package com.nexus.presence.infrastructure.amqp;

import com.nexus.common.messaging.EventTopology;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Khai báo exchange community/realtime/messaging/presence.events và ba queue quorum cùng DLQ
@Configuration
public class PresenceTopologyConfig {

  // Exchange phải có trước khi bind, routing key theo event-catalog
  @Bean
  Declarables presenceTopology() {
    List<Declarable> all = new ArrayList<>();
    all.addAll(EventTopology.exchange("presence.events").getDeclarables());
    all.addAll(EventTopology.exchange("realtime.events").getDeclarables());
    all.addAll(
        EventTopology.consumerQueue(
                "presence.realtime.events", "realtime.events", "session.*", "gateway.heartbeat")
            .getDeclarables());
    all.addAll(EventTopology.exchange("community.events").getDeclarables());
    all.addAll(
        EventTopology.consumerQueue(
                "presence.community.events",
                "community.events",
                "member.joined",
                "member.left",
                "member.kicked",
                "member.banned",
                "server.deleted")
            .getDeclarables());
    all.addAll(EventTopology.exchange("messaging.events").getDeclarables());
    all.addAll(
        EventTopology.consumerQueue(
                "presence.messaging.events",
                "messaging.events",
                "friend-request.accepted",
                "friend.removed")
            .getDeclarables());
    return new Declarables(all);
  }
}

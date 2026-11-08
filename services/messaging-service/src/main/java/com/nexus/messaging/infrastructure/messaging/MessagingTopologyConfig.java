package com.nexus.messaging.infrastructure.messaging;

import com.nexus.common.messaging.EventTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Khai báo exchange community.events và queue messaging.community.events kèm DLQ
@Configuration
class MessagingTopologyConfig {

  // Khai báo idempotent để queue bind được khi community chưa chạy
  @Bean
  Declarables communityEventsExchange() {
    return EventTopology.exchange("community.events");
  }

  // Queue quorum bind 10 routing key mà messaging cần
  @Bean
  Declarables communityEventsQueue() {
    return EventTopology.consumerQueue(
        "messaging.community.events",
        "community.events",
        "channel.created",
        "channel.deleted",
        "channel.permissions-changed",
        "server.deleted",
        "member.left",
        "member.kicked",
        "member.banned",
        "member.roles-changed",
        "role.updated",
        "role.deleted");
  }
}

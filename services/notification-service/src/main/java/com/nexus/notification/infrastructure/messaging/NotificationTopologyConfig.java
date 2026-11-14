package com.nexus.notification.infrastructure.messaging;

import com.nexus.common.messaging.EventTopology;
import com.nexus.common.messaging.InboxGuard;
import com.nexus.notification.application.port.EventInbox;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Khai báo exchange, queue community và messaging của notification kèm DLQ và inbox từng queue
@Configuration
class NotificationTopologyConfig {

  static final String COMMUNITY_QUEUE = "notification.community.events";
  static final String MESSAGING_QUEUE = "notification.messaging.events";

  // Khai báo idempotent để queue bind được khi community chưa chạy
  @Bean
  Declarables communityEventsExchange() {
    return EventTopology.exchange("community.events");
  }

  // Queue quorum bind 7 routing key mà notification cần
  @Bean
  Declarables communityEventsQueue() {
    return EventTopology.consumerQueue(
        COMMUNITY_QUEUE,
        "community.events",
        "channel.created",
        "channel.deleted",
        "member.joined",
        "member.left",
        "member.kicked",
        "member.banned",
        "server.deleted");
  }

  // Inbox của consumer notification.community.events
  @Bean
  EventInbox communityEventInbox(InboxGuard guard) {
    return new LibEventInbox(guard, COMMUNITY_QUEUE);
  }

  // Khai báo idempotent để queue bind được khi messaging chưa chạy
  @Bean
  Declarables messagingEventsExchange() {
    return EventTopology.exchange("messaging.events");
  }

  // Queue quorum bind tin mới và kênh DM mới
  @Bean
  Declarables messagingEventsQueue() {
    return EventTopology.consumerQueue(
        MESSAGING_QUEUE, "messaging.events", "message.created", "dm-channel.created");
  }

  // Inbox của consumer notification.messaging.events
  @Bean
  EventInbox messagingEventInbox(InboxGuard guard) {
    return new LibEventInbox(guard, MESSAGING_QUEUE);
  }
}

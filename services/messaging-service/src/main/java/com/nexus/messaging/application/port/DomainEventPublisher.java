package com.nexus.messaging.application.port;

import com.nexus.messaging.domain.shared.DomainEvent;

// Cổng phát sự kiện miền ra ngoài service
public interface DomainEventPublisher {

  // Phát một sự kiện miền
  void publish(DomainEvent event);
}

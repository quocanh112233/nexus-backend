package com.nexus.identity.application.port;

import com.nexus.identity.domain.shared.DomainEvent;

// Cổng phát sự kiện miền ra ngoài service
public interface DomainEventPublisher {

  // Phát một sự kiện miền trong transaction của use case
  void publish(DomainEvent event);
}

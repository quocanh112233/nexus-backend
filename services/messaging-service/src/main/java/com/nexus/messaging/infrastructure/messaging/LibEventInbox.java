package com.nexus.messaging.infrastructure.messaging;

import com.nexus.common.messaging.InboxGuard;
import com.nexus.messaging.application.port.EventInbox;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Inbox của consumer messaging.community.events dựa trên InboxGuard của lib
@Component
public class LibEventInbox implements EventInbox {

  private static final String CONSUMER = "messaging.community.events";

  private final InboxGuard guard;

  // Nhận InboxGuard
  public LibEventInbox(InboxGuard guard) {
    this.guard = guard;
  }

  // Ghi processed_event, trả true nếu là lần đầu
  @Override
  public boolean firstDelivery(UUID eventId) {
    return guard.firstDelivery(eventId, CONSUMER);
  }
}

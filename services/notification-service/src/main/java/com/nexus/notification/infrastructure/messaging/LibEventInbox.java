package com.nexus.notification.infrastructure.messaging;

import com.nexus.common.messaging.InboxGuard;
import com.nexus.notification.application.port.EventInbox;
import java.util.UUID;

// Inbox của một consumer notification dựa trên InboxGuard của lib, tên consumer là tên queue
public class LibEventInbox implements EventInbox {

  private final InboxGuard guard;
  private final String consumer;

  // Nhận InboxGuard và tên consumer
  public LibEventInbox(InboxGuard guard, String consumer) {
    this.guard = guard;
    this.consumer = consumer;
  }

  // Ghi processed_event, trả true nếu là lần đầu
  @Override
  public boolean firstDelivery(UUID eventId) {
    return guard.firstDelivery(eventId, consumer);
  }
}

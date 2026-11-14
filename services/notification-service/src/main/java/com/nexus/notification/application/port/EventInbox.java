package com.nexus.notification.application.port;

import java.util.UUID;

// Cổng chặn xử lý trùng event, cùng transaction với nghiệp vụ
public interface EventInbox {

  // True nếu đây là lần đầu nhận eventId
  boolean firstDelivery(UUID eventId);
}

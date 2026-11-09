package com.nexus.realtime.infrastructure.stomp;

import com.nexus.realtime.infrastructure.amqp.PresenceReporter;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

// Chuyển sự kiện mở và đóng session WebSocket cho PresenceReporter
@Component
public class SessionLifecycleListener {

  private final PresenceReporter reporter;

  // Nhận reporter
  public SessionLifecycleListener(PresenceReporter reporter) {
    this.reporter = reporter;
  }

  // Session đã CONNECTED, bỏ qua nếu không có Principal
  @EventListener
  void onConnected(SessionConnectedEvent event) {
    Principal user = event.getUser();
    if (user != null) {
      reporter.opened(
          StompHeaderAccessor.wrap(event.getMessage()).getSessionId(),
          UUID.fromString(user.getName()));
    }
  }

  // Session đóng, dù DISCONNECT êm hay đứt đột ngột
  @EventListener
  void onDisconnect(SessionDisconnectEvent event) {
    reporter.closed(event.getSessionId());
  }
}

package com.nexus.realtime.interfaces.stomp;

import com.nexus.realtime.application.typing.TypingPublisher;
import com.nexus.realtime.application.typing.TypingService;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

// Nhận frame SEND /app/channels/{id}/typing và phát TypingStarted nếu được TypingService chấp nhận
@Controller
public class TypingController {

  private final TypingService typing;
  private final TypingPublisher publisher;
  private final Clock clock;

  // Nhận dịch vụ throttle, cổng phát event và đồng hồ
  public TypingController(TypingService typing, TypingPublisher publisher, Clock clock) {
    this.typing = typing;
    this.publisher = publisher;
    this.clock = clock;
  }

  // Lấy userId từ Principal của session rồi phát nếu hợp lệ
  @MessageMapping("/channels/{channelId}/typing")
  public void typing(@DestinationVariable UUID channelId, Principal principal) {
    UUID userId = UUID.fromString(principal.getName());
    if (typing.accept(userId, channelId)) {
      publisher.publishTyping(channelId, userId, clock.instant());
    }
  }
}

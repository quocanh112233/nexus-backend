package com.nexus.notification.interfaces.rest;

import com.nexus.notification.application.readstate.ListReadStatesQuery;
import com.nexus.notification.application.readstate.MarkChannelReadUseCase;
import com.nexus.notification.domain.readstate.ReadStateSnapshot;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.UserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint liệt kê và đánh dấu đã đọc read state của user hiện tại
@RestController
@RequestMapping("/api/v1/read-states")
class ReadStateController {

  private final ListReadStatesQuery listReadStates;
  private final MarkChannelReadUseCase markChannelRead;

  // Nhận query và use case qua constructor
  ReadStateController(ListReadStatesQuery listReadStates, MarkChannelReadUseCase markChannelRead) {
    this.listReadStates = listReadStates;
    this.markChannelRead = markChannelRead;
  }

  // Read state của mọi kênh user truy cập được, kênh đã có tin
  @GetMapping
  ReadStateResponse.Items list(@AuthenticationPrincipal Jwt jwt) {
    return new ReadStateResponse.Items(
        listReadStates.execute(UserId.of(jwt.getSubject())).stream()
            .map(ReadStateResponse::from)
            .toList());
  }

  // Đánh dấu đã đọc tới tin lastReadMessageId, mốc không lùi
  @PutMapping("/{channelId}")
  ReadStateResponse.Marked mark(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @Valid @RequestBody MarkReadBody body) {
    ReadStateSnapshot snapshot =
        markChannelRead.execute(
            UserId.of(jwt.getSubject()),
            new ChannelId(channelId),
            new MessageId(body.lastReadMessageId()));
    return new ReadStateResponse.Marked(
        channelId, snapshot.lastRead().value(), snapshot.mentionCount());
  }
}

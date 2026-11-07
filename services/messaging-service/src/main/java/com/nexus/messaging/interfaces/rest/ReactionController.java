package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.reaction.AddReactionUseCase;
import com.nexus.messaging.application.reaction.RemoveReactionUseCase;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint thả và gỡ reaction của chính user, emoji nằm trên URL và luôn trả 204
@RestController
@RequestMapping("/api/v1/channels/{channelId}/messages/{messageId}/reactions/{emoji}")
class ReactionController {

  private final AddReactionUseCase addReaction;
  private final RemoveReactionUseCase removeReaction;

  // Nhận các use case qua constructor
  ReactionController(AddReactionUseCase addReaction, RemoveReactionUseCase removeReaction) {
    this.addReaction = addReaction;
    this.removeReaction = removeReaction;
  }

  // Thả emoji, kiểm emoji trước khi chạm DB
  @PutMapping
  ResponseEntity<Void> add(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @PathVariable UUID messageId,
      @PathVariable String emoji) {
    addReaction.execute(
        new ChannelId(channelId),
        new MessageId(messageId),
        UserId.of(jwt.getSubject()),
        new Emoji(emoji));
    return ResponseEntity.noContent().build();
  }

  // Gỡ emoji của chính user, reaction của người khác không bị đụng tới
  @DeleteMapping
  ResponseEntity<Void> remove(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @PathVariable UUID messageId,
      @PathVariable String emoji) {
    removeReaction.execute(
        new ChannelId(channelId),
        new MessageId(messageId),
        UserId.of(jwt.getSubject()),
        new Emoji(emoji));
    return ResponseEntity.noContent().build();
  }
}

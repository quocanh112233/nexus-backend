package com.nexus.messaging.interfaces.rest;

import com.nexus.messaging.application.attachment.AttachmentInput;
import com.nexus.messaging.application.message.DeleteMessageUseCase;
import com.nexus.messaging.application.message.EditMessageUseCase;
import com.nexus.messaging.application.message.ListMessagesQuery;
import com.nexus.messaging.application.message.SendMessageCommand;
import com.nexus.messaging.application.message.SendMessageUseCase;
import com.nexus.messaging.application.message.SendMessageUseCase.SendResult;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint gửi, sửa, xóa, đọc lịch sử tin trong kênh; gửi trả 201 khi tạo, 200 khi nonce trùng
@RestController
@RequestMapping("/api/v1/channels/{channelId}/messages")
class MessageController {

  private final SendMessageUseCase sendMessage;
  private final EditMessageUseCase editMessage;
  private final DeleteMessageUseCase deleteMessage;
  private final ListMessagesQuery listMessages;

  // Nhận các use case qua constructor
  MessageController(
      SendMessageUseCase sendMessage,
      EditMessageUseCase editMessage,
      DeleteMessageUseCase deleteMessage,
      ListMessagesQuery listMessages) {
    this.sendMessage = sendMessage;
    this.editMessage = editMessage;
    this.deleteMessage = deleteMessage;
    this.listMessages = listMessages;
  }

  // Gửi tin; đua nonce thì bên thua vi phạm unique và gọi lại để đọc tin đã có, không nonce thì ném
  @PostMapping
  ResponseEntity<MessageResponse> send(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @Valid @RequestBody SendMessageRequest request) {
    SendMessageCommand command =
        new SendMessageCommand(
            new ChannelId(channelId),
            UserId.of(jwt.getSubject()),
            request.content(),
            request.nonce() == null ? null : new Nonce(request.nonce()),
            request.replyToId() == null ? null : new MessageId(request.replyToId()),
            attachments(request));
    SendResult result;
    try {
      result = sendMessage.execute(command);
    } catch (DataIntegrityViolationException e) {
      if (command.nonce() == null) {
        throw e;
      }
      result = sendMessage.execute(command);
    }
    MessageResponse body = MessageResponse.from(result.message());
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(
            URI.create("/api/v1/channels/" + channelId + "/messages/" + body.id()))
        .body(body);
  }

  // Đổi danh sách attachment của request sang input của use case, null thành rỗng
  private static List<AttachmentInput> attachments(SendMessageRequest request) {
    return request.attachments() == null
        ? List.of()
        : request.attachments().stream()
            .map(a -> new AttachmentInput(a.publicId(), a.resourceType(), a.originalFilename()))
            .toList();
  }

  // Sửa nội dung tin, chỉ tác giả được sửa
  @PatchMapping("/{messageId}")
  MessageResponse edit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @PathVariable UUID messageId,
      @Valid @RequestBody EditMessageRequest request) {
    return MessageResponse.from(
        editMessage.execute(
            new ChannelId(channelId),
            new MessageId(messageId),
            UserId.of(jwt.getSubject()),
            request.content()));
  }

  // Xóa mềm tin, trả 204 kể cả khi tin đã xóa
  @DeleteMapping("/{messageId}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @PathVariable UUID messageId) {
    deleteMessage.execute(
        new ChannelId(channelId), new MessageId(messageId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // Lịch sử tin phân trang keyset, before hoặc không con trỏ thì giảm dần, after thì tăng dần
  @GetMapping
  List<MessageResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID channelId,
      @RequestParam(required = false) String before,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit) {
    return listMessages
        .execute(
            new ChannelId(channelId),
            UserId.of(jwt.getSubject()),
            cursor(before, "before"),
            cursor(after, "after"),
            limit)
        .stream()
        .map(MessageResponse::from)
        .toList();
  }

  // Đổi con trỏ chuỗi sang MessageId, không phải UUID thì 400
  private static MessageId cursor(String value, String name) {
    if (value == null) {
      return null;
    }
    try {
      return new MessageId(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException(name + " must be a UUID");
    }
  }
}

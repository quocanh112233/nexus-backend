package com.nexus.community.interfaces.rest;

import com.nexus.community.application.channel.CreateChannelUseCase;
import com.nexus.community.application.channel.DeleteChannelUseCase;
import com.nexus.community.application.channel.UpdateChannelUseCase;
import com.nexus.community.application.server.ChannelView;
import com.nexus.community.domain.category.CategoryId;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.ChannelName;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint tạo, sửa và xóa kênh của server, cần quyền MANAGE_CHANNELS
@RestController
@RequestMapping("/api/v1/servers/{serverId}/channels")
class ChannelController {

  private final CreateChannelUseCase createChannel;
  private final UpdateChannelUseCase updateChannel;
  private final DeleteChannelUseCase deleteChannel;

  // Nhận các use case qua constructor
  ChannelController(
      CreateChannelUseCase createChannel,
      UpdateChannelUseCase updateChannel,
      DeleteChannelUseCase deleteChannel) {
    this.createChannel = createChannel;
    this.updateChannel = updateChannel;
    this.deleteChannel = deleteChannel;
  }

  // Tạo kênh, trả 201 kèm Location
  @PostMapping
  ResponseEntity<ChannelResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @Valid @RequestBody CreateChannelRequest request) {
    ChannelView view =
        createChannel.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            new ChannelName(request.name()),
            request.topic(),
            request.categoryId() == null ? null : CategoryId.of(request.categoryId()),
            request.position());
    return ResponseEntity.created(
            URI.create("/api/v1/servers/" + serverId + "/channels/" + view.id()))
        .body(ChannelResponse.from(serverId, view));
  }

  // Sửa kênh một phần, gồm chuyển category hoặc về không category
  @PatchMapping("/{channelId}")
  ChannelResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID channelId,
      @Valid @RequestBody UpdateChannelRequest request) {
    return ChannelResponse.from(
        serverId,
        updateChannel.execute(
            new ServerId(serverId),
            new ChannelId(channelId),
            UserId.of(jwt.getSubject()),
            request.toChanges()));
  }

  // Xóa kênh, trả 204
  @DeleteMapping("/{channelId}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId, @PathVariable UUID channelId) {
    deleteChannel.execute(
        new ServerId(serverId), new ChannelId(channelId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}

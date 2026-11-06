package com.nexus.community.interfaces.rest;

import com.nexus.community.application.channel.RemoveChannelOverwriteUseCase;
import com.nexus.community.application.channel.SetChannelOverwriteUseCase;
import com.nexus.community.domain.channel.ChannelId;
import com.nexus.community.domain.channel.OverwriteTarget;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint đặt và gỡ overwrite quyền của role hoặc member trên kênh, cần MANAGE_ROLES
@RestController
@RequestMapping(
    "/api/v1/servers/{serverId}/channels/{channelId}/permissions/{targetType}/{targetId}")
class OverwriteController {

  private final SetChannelOverwriteUseCase setOverwrite;
  private final RemoveChannelOverwriteUseCase removeOverwrite;

  // Nhận các use case qua constructor
  OverwriteController(
      SetChannelOverwriteUseCase setOverwrite, RemoveChannelOverwriteUseCase removeOverwrite) {
    this.setOverwrite = setOverwrite;
    this.removeOverwrite = removeOverwrite;
  }

  // Đặt overwrite, ghi đè nếu đã có, trả 204
  @PutMapping
  ResponseEntity<Void> put(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID channelId,
      @PathVariable String targetType,
      @PathVariable UUID targetId,
      @RequestBody OverwriteRequest request) {
    setOverwrite.execute(
        new ServerId(serverId),
        new ChannelId(channelId),
        parseType(targetType),
        targetId,
        request.allow(),
        request.deny(),
        UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // Gỡ overwrite, trả 204, không có thì 404
  @DeleteMapping
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID channelId,
      @PathVariable String targetType,
      @PathVariable UUID targetId) {
    removeOverwrite.execute(
        new ServerId(serverId),
        new ChannelId(channelId),
        parseType(targetType),
        targetId,
        UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // Đổi role hoặc member không phân biệt hoa thường thành loại đích, giá trị lạ thì 400
  private static OverwriteTarget parseType(String text) {
    try {
      return OverwriteTarget.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainValidationException("targetType must be role or member");
    }
  }
}

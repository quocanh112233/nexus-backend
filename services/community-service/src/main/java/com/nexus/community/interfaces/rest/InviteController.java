package com.nexus.community.interfaces.rest;

import com.nexus.community.application.invite.AcceptInviteUseCase;
import com.nexus.community.application.invite.CreateInviteUseCase;
import com.nexus.community.application.invite.InviteView;
import com.nexus.community.application.invite.ListInvitesQuery;
import com.nexus.community.application.invite.PreviewInviteQuery;
import com.nexus.community.application.invite.RevokeInviteUseCase;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Endpoint tạo, liệt kê, xem trước, tham gia và thu hồi lời mời
@RestController
class InviteController {

  private final CreateInviteUseCase createInvite;
  private final ListInvitesQuery listInvites;
  private final PreviewInviteQuery previewInvite;
  private final AcceptInviteUseCase acceptInvite;
  private final RevokeInviteUseCase revokeInvite;

  // Nhận các use case và truy vấn qua constructor
  InviteController(
      CreateInviteUseCase createInvite,
      ListInvitesQuery listInvites,
      PreviewInviteQuery previewInvite,
      AcceptInviteUseCase acceptInvite,
      RevokeInviteUseCase revokeInvite) {
    this.createInvite = createInvite;
    this.listInvites = listInvites;
    this.previewInvite = previewInvite;
    this.acceptInvite = acceptInvite;
    this.revokeInvite = revokeInvite;
  }

  // Người có quyền CREATE_INVITE tạo lời mời, body vắng thì dùng mặc định, trả 201
  @PostMapping("/api/v1/servers/{serverId}/invites")
  ResponseEntity<InviteResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @Valid @RequestBody(required = false) CreateInviteRequest request) {
    CreateInviteRequest body = request == null ? new CreateInviteRequest(null, null) : request;
    InviteView view =
        createInvite.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            body.maxUsesOrDefault(),
            body.expiresInSecondsOrDefault());
    return ResponseEntity.status(HttpStatus.CREATED).body(InviteResponse.from(view));
  }

  // Người có quyền MANAGE_SERVER liệt kê lời mời, mới tạo trước
  @GetMapping("/api/v1/servers/{serverId}/invites")
  List<InviteResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    return listInvites.execute(new ServerId(serverId), UserId.of(jwt.getSubject())).stream()
        .map(InviteResponse::from)
        .toList();
  }

  // Xem trước lời mời công khai, không cần JWT
  @GetMapping("/api/v1/invites/{code}")
  InvitePreviewResponse preview(@PathVariable String code) {
    return InvitePreviewResponse.from(previewInvite.execute(code));
  }

  // Tham gia server bằng lời mời, trả tóm tắt server
  @PostMapping("/api/v1/invites/{code}/accept")
  ServerSummaryResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
    return ServerSummaryResponse.from(acceptInvite.execute(code, UserId.of(jwt.getSubject())));
  }

  // Người tạo hoặc người có quyền MANAGE_SERVER thu hồi lời mời, trả 204
  @DeleteMapping("/api/v1/invites/{code}")
  ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
    revokeInvite.execute(code, UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}

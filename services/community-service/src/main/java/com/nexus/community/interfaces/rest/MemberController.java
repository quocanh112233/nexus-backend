package com.nexus.community.interfaces.rest;

import com.nexus.community.application.member.LeaveServerUseCase;
import com.nexus.community.application.member.ListMembersQuery;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.DomainValidationException;
import com.nexus.community.domain.shared.UserId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint rời server và liệt kê thành viên theo con trỏ
@RestController
class MemberController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final LeaveServerUseCase leaveServer;
  private final ListMembersQuery listMembers;

  // Nhận use case và truy vấn qua constructor
  MemberController(LeaveServerUseCase leaveServer, ListMembersQuery listMembers) {
    this.leaveServer = leaveServer;
    this.listMembers = listMembers;
  }

  // Người gọi rời server, trả 204
  @DeleteMapping("/api/v1/servers/{serverId}/members/@me")
  ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    leaveServer.execute(new ServerId(serverId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // Thành viên xem một trang thành viên, limit 1..100 mặc định 50, cursor sai thì 400
  @GetMapping("/api/v1/servers/{serverId}/members")
  MemberPageResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new DomainValidationException("limit must be between 1 and " + MAX_LIMIT);
    }
    return MemberPageResponse.from(
        listMembers.execute(
            new ServerId(serverId), UserId.of(jwt.getSubject()), decodeCursor(cursor), size));
  }

  // Giải mã con trỏ base64url thành userId, null là trang đầu, sai định dạng thì 400
  private static UserId decodeCursor(String cursor) {
    if (cursor == null) {
      return null;
    }
    try {
      return UserId.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException | DomainValidationException e) {
      throw new DomainValidationException("cursor is malformed");
    }
  }
}

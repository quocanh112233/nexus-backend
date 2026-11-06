package com.nexus.community.interfaces.rest;

import com.nexus.community.application.member.AssignRolesUseCase;
import com.nexus.community.application.member.KickMemberUseCase;
import com.nexus.community.application.member.LeaveServerUseCase;
import com.nexus.community.application.member.ListMembersQuery;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint rời server, kick, liệt kê thành viên theo con trỏ và gán role cho thành viên
@RestController
class MemberController {

  private final LeaveServerUseCase leaveServer;
  private final KickMemberUseCase kickMember;
  private final ListMembersQuery listMembers;
  private final AssignRolesUseCase assignRoles;

  // Nhận use case và truy vấn qua constructor
  MemberController(
      LeaveServerUseCase leaveServer,
      KickMemberUseCase kickMember,
      ListMembersQuery listMembers,
      AssignRolesUseCase assignRoles) {
    this.leaveServer = leaveServer;
    this.kickMember = kickMember;
    this.listMembers = listMembers;
    this.assignRoles = assignRoles;
  }

  // Người gọi rời server, trả 204
  @DeleteMapping("/api/v1/servers/{serverId}/members/@me")
  ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    leaveServer.execute(new ServerId(serverId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // Kick thành viên theo userId, trả 204, @me là rời server nên khớp mapping literal ở trên
  @DeleteMapping("/api/v1/servers/{serverId}/members/{userId}")
  ResponseEntity<Void> kick(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId, @PathVariable UUID userId) {
    kickMember.execute(new ServerId(serverId), UserId.of(jwt.getSubject()), new UserId(userId));
    return ResponseEntity.noContent().build();
  }

  // Thành viên xem một trang thành viên, limit 1..100 mặc định 50, cursor sai thì 400
  @GetMapping("/api/v1/servers/{serverId}/members")
  MemberPageResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return MemberPageResponse.from(
        listMembers.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            Cursors.decode(cursor),
            Cursors.limit(limit)));
  }

  // Thay tập role của thành viên, trả 200 kèm thành viên sau khi đổi
  @PutMapping("/api/v1/servers/{serverId}/members/{userId}/roles")
  MemberResponse assignRoles(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID userId,
      @Valid @RequestBody AssignRolesRequest request) {
    return MemberResponse.from(
        assignRoles.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            new UserId(userId),
            request.roleIds().stream().map(RoleId::new).toList()));
  }
}

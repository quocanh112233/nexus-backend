package com.nexus.community.interfaces.rest;

import com.nexus.community.application.ban.BanMemberUseCase;
import com.nexus.community.application.ban.ListBansQuery;
import com.nexus.community.application.ban.UnbanMemberUseCase;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
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

// Endpoint ban, gỡ ban và liệt kê ban của server
@RestController
class BanController {

  private final BanMemberUseCase banMember;
  private final UnbanMemberUseCase unbanMember;
  private final ListBansQuery listBans;

  // Nhận use case và truy vấn qua constructor
  BanController(
      BanMemberUseCase banMember, UnbanMemberUseCase unbanMember, ListBansQuery listBans) {
    this.banMember = banMember;
    this.unbanMember = unbanMember;
    this.listBans = listBans;
  }

  // Ban user kèm lý do tùy chọn, ban lại người đã bị ban vẫn trả 204
  @PutMapping("/api/v1/servers/{serverId}/bans/{userId}")
  ResponseEntity<Void> ban(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID userId,
      @RequestBody(required = false) BanRequest request) {
    banMember.execute(
        new ServerId(serverId),
        UserId.of(jwt.getSubject()),
        new UserId(userId),
        request == null ? null : request.reason());
    return ResponseEntity.noContent().build();
  }

  // Gỡ ban user, không bị ban thì 404
  @DeleteMapping("/api/v1/servers/{serverId}/bans/{userId}")
  ResponseEntity<Void> unban(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId, @PathVariable UUID userId) {
    unbanMember.execute(new ServerId(serverId), UserId.of(jwt.getSubject()), new UserId(userId));
    return ResponseEntity.noContent().build();
  }

  // Xem một trang ban, limit 1..100 mặc định 50, cursor sai thì 400
  @GetMapping("/api/v1/servers/{serverId}/bans")
  BanPageResponse list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return BanPageResponse.from(
        listBans.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            Cursors.decode(cursor),
            Cursors.limit(limit)));
  }
}

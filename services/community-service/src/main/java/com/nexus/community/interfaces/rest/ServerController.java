package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.CreateServerCommand;
import com.nexus.community.application.server.CreateServerUseCase;
import com.nexus.community.application.server.DeleteServerUseCase;
import com.nexus.community.application.server.GetServerQuery;
import com.nexus.community.application.server.ListMyServersQuery;
import com.nexus.community.application.server.MyPermissionsQuery;
import com.nexus.community.application.server.RenameServerUseCase;
import com.nexus.community.application.server.ServerDetail;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

// Endpoint tạo, liệt kê, xem, xem quyền, đổi tên và xóa server, userId lấy từ sub của JWT
@RestController
@RequestMapping("/api/v1/servers")
class ServerController {

  private final CreateServerUseCase createServer;
  private final ListMyServersQuery listMyServers;
  private final GetServerQuery getServer;
  private final RenameServerUseCase renameServer;
  private final DeleteServerUseCase deleteServer;
  private final MyPermissionsQuery myPermissions;

  // Nhận use case và truy vấn qua constructor
  ServerController(
      CreateServerUseCase createServer,
      ListMyServersQuery listMyServers,
      GetServerQuery getServer,
      RenameServerUseCase renameServer,
      DeleteServerUseCase deleteServer,
      MyPermissionsQuery myPermissions) {
    this.createServer = createServer;
    this.listMyServers = listMyServers;
    this.getServer = getServer;
    this.renameServer = renameServer;
    this.deleteServer = deleteServer;
    this.myPermissions = myPermissions;
  }

  // Tạo server cho người gọi, trả 201 kèm Location tới server
  @PostMapping
  ResponseEntity<ServerDetailResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateServerRequest request) {
    ServerDetail detail =
        createServer.execute(new CreateServerCommand(UserId.of(jwt.getSubject()), request.name()));
    return ResponseEntity.created(URI.create("/api/v1/servers/" + detail.server().id()))
        .body(ServerDetailResponse.from(detail));
  }

  // Liệt kê server mà người gọi là thành viên
  @GetMapping
  List<ServerSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return listMyServers.execute(UserId.of(jwt.getSubject())).stream()
        .map(ServerSummaryResponse::from)
        .toList();
  }

  // Xem chi tiết một server mà người gọi là thành viên
  @GetMapping("/{serverId}")
  ServerDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    return ServerDetailResponse.from(
        getServer.execute(new ServerId(serverId), UserId.of(jwt.getSubject())));
  }

  // Xem quyền hiệu lực của người gọi trong server và trên từng kênh
  @GetMapping("/{serverId}/permissions/@me")
  MyPermissionsResponse permissions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    return MyPermissionsResponse.from(
        myPermissions.execute(new ServerId(serverId), UserId.of(jwt.getSubject())));
  }

  // Đổi tên server, cần quyền MANAGE_SERVER
  @PatchMapping("/{serverId}")
  ServerSummaryResponse rename(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @Valid @RequestBody RenameServerRequest request) {
    return ServerSummaryResponse.from(
        renameServer.execute(new ServerId(serverId), UserId.of(jwt.getSubject()), request.name()));
  }

  // Xóa server, chỉ chủ server được phép, trả 204
  @DeleteMapping("/{serverId}")
  ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    deleteServer.execute(new ServerId(serverId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}

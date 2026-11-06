package com.nexus.community.interfaces.rest;

import com.nexus.community.application.server.CreateRoleUseCase;
import com.nexus.community.application.server.DeleteRoleUseCase;
import com.nexus.community.application.server.ListRolesQuery;
import com.nexus.community.application.server.RoleView;
import com.nexus.community.application.server.UpdateRoleUseCase;
import com.nexus.community.domain.server.RoleId;
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

// Endpoint liệt kê, tạo, sửa và xóa role của server theo luật thứ bậc
@RestController
@RequestMapping("/api/v1/servers/{serverId}/roles")
class RoleController {

  private final ListRolesQuery listRoles;
  private final CreateRoleUseCase createRole;
  private final UpdateRoleUseCase updateRole;
  private final DeleteRoleUseCase deleteRole;

  // Nhận truy vấn và các use case qua constructor
  RoleController(
      ListRolesQuery listRoles,
      CreateRoleUseCase createRole,
      UpdateRoleUseCase updateRole,
      DeleteRoleUseCase deleteRole) {
    this.listRoles = listRoles;
    this.createRole = createRole;
    this.updateRole = updateRole;
    this.deleteRole = deleteRole;
  }

  // Liệt kê role theo vị trí tăng dần cho mọi thành viên
  @GetMapping
  List<RoleResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId) {
    return listRoles.execute(new ServerId(serverId), UserId.of(jwt.getSubject())).stream()
        .map(RoleResponse::from)
        .toList();
  }

  // Tạo role, trả 201 kèm Location
  @PostMapping
  ResponseEntity<RoleResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @Valid @RequestBody CreateRoleRequest request) {
    RoleView view =
        createRole.execute(
            new ServerId(serverId),
            UserId.of(jwt.getSubject()),
            request.name(),
            request.color() == null ? 0 : request.color(),
            request.permissionSet(),
            request.position());
    return ResponseEntity.created(URI.create("/api/v1/servers/" + serverId + "/roles/" + view.id()))
        .body(RoleResponse.from(view));
  }

  // Sửa role một phần
  @PatchMapping("/{roleId}")
  RoleResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID serverId,
      @PathVariable UUID roleId,
      @Valid @RequestBody UpdateRoleRequest request) {
    return RoleResponse.from(
        updateRole.execute(
            new ServerId(serverId),
            new RoleId(roleId),
            UserId.of(jwt.getSubject()),
            request.toChanges()));
  }

  // Xóa role, trả 204
  @DeleteMapping("/{roleId}")
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID serverId, @PathVariable UUID roleId) {
    deleteRole.execute(new ServerId(serverId), new RoleId(roleId), UserId.of(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}

package com.nexus.community.infrastructure.persistence.server;

import com.nexus.community.domain.server.PermissionSet;
import com.nexus.community.domain.server.Role;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerName;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Component;

// Chuyển đổi hai chiều giữa aggregate Server, Role và các JpaEntity
@Component
final class ServerMapper {

  // Dựng lại aggregate từ entity server và các role của nó
  Server toDomain(ServerJpaEntity e, List<RoleJpaEntity> roles) {
    return Server.rehydrate(
        new ServerId(e.id),
        new ServerName(e.name),
        new UserId(e.ownerId),
        roles.stream().map(this::toRole).toList(),
        e.createdAt,
        e.updatedAt,
        e.version);
  }

  // Dựng entity server từ aggregate
  ServerJpaEntity toJpa(Server s) {
    ServerJpaEntity e = new ServerJpaEntity();
    e.id = s.id().value();
    e.name = s.name().value();
    e.ownerId = s.ownerId().value();
    e.createdAt = s.createdAt();
    e.updatedAt = s.updatedAt();
    e.version = s.version();
    return e;
  }

  // Dựng các entity role của server, created_at lấy theo updatedAt của server
  List<RoleJpaEntity> toRoleJpa(Server s) {
    return s.roles().stream()
        .map(
            r -> {
              RoleJpaEntity e = new RoleJpaEntity();
              e.id = r.id().value();
              e.serverId = s.id().value();
              e.name = r.name();
              e.color = r.color();
              e.permissions = r.permissions().bits();
              e.position = r.position();
              e.isDefault = r.isDefault();
              e.createdAt = s.updatedAt();
              return e;
            })
        .toList();
  }

  // Chép các trường đổi được từ entity role mới sang entity role đã có (giữ created_at)
  void copyRole(RoleJpaEntity from, RoleJpaEntity to) {
    to.name = from.name;
    to.color = from.color;
    to.permissions = from.permissions;
    to.position = from.position;
  }

  // Chuyển một entity role thành record Role
  private Role toRole(RoleJpaEntity e) {
    return new Role(
        new RoleId(e.id),
        e.name,
        e.color,
        new PermissionSet(e.permissions),
        e.position,
        e.isDefault);
  }
}

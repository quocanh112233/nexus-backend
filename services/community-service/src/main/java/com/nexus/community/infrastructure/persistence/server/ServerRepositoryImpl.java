package com.nexus.community.infrastructure.persistence.server;

import com.nexus.community.domain.server.Server;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.server.ServerRepository;
import com.nexus.community.domain.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

// Cài đặt ServerRepository bằng ServerDao, RoleDao và ServerMapper
@Repository
class ServerRepositoryImpl implements ServerRepository {

  private final ServerDao dao;
  private final RoleDao roleDao;
  private final ServerMapper mapper;

  // Nhận DAO và mapper qua constructor
  ServerRepositoryImpl(ServerDao dao, RoleDao roleDao, ServerMapper mapper) {
    this.dao = dao;
    this.roleDao = roleDao;
    this.mapper = mapper;
  }

  // Lưu và flush ngay; server mới persist cùng role, server cũ merge theo version rồi đồng bộ role
  @Override
  public void save(Server server) {
    ServerJpaEntity entity = mapper.toJpa(server);
    boolean isNew = !dao.existsById(entity.id);
    if (isNew) {
      entity.version = null;
    }
    dao.saveAndFlush(entity);
    if (isNew) {
      roleDao.saveAll(mapper.toRoleJpa(server));
    } else {
      syncRoles(server);
    }
  }

  // Xóa role không còn trong aggregate, cập nhật role cũ và thêm role mới
  private void syncRoles(Server server) {
    Map<UUID, RoleJpaEntity> existing =
        roleDao.findAllByServerIdIn(List.of(server.id().value())).stream()
            .collect(Collectors.toMap(r -> r.id, r -> r));
    List<RoleJpaEntity> wanted = mapper.toRoleJpa(server);
    Set<UUID> wantedIds = wanted.stream().map(r -> r.id).collect(Collectors.toSet());
    roleDao.deleteAll(existing.values().stream().filter(r -> !wantedIds.contains(r.id)).toList());
    List<RoleJpaEntity> toSave = new ArrayList<>();
    for (RoleJpaEntity role : wanted) {
      RoleJpaEntity old = existing.get(role.id);
      if (old != null) {
        mapper.copyRole(role, old);
      }
      toSave.add(old != null ? old : role);
    }
    roleDao.saveAll(toSave);
  }

  // Tìm server theo id kèm role
  @Override
  public Optional<Server> findById(ServerId id) {
    return dao.findById(id.value()).map(e -> toDomain(e, rolesOf(List.of(e))));
  }

  // Xóa server theo id
  @Override
  public void delete(ServerId id) {
    dao.deleteById(id.value());
  }

  // Liệt kê server của thành viên, nạp role một lượt
  @Override
  public List<Server> findAllByMember(UserId userId) {
    List<ServerJpaEntity> entities = dao.findAllByMember(userId.value());
    Map<UUID, List<RoleJpaEntity>> roles = rolesOf(entities);
    return entities.stream().map(e -> toDomain(e, roles)).toList();
  }

  // Gom role của các server theo serverId
  private Map<UUID, List<RoleJpaEntity>> rolesOf(List<ServerJpaEntity> entities) {
    return roleDao.findAllByServerIdIn(entities.stream().map(e -> e.id).toList()).stream()
        .collect(Collectors.groupingBy(r -> r.serverId));
  }

  // Dựng aggregate từ entity và nhóm role đã gom
  private Server toDomain(ServerJpaEntity e, Map<UUID, List<RoleJpaEntity>> roles) {
    return mapper.toDomain(e, roles.getOrDefault(e.id, List.of()));
  }
}

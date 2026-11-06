package com.nexus.community.infrastructure.persistence.member;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.RoleId;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

// Cài đặt MemberRepository bằng MemberDao
@Repository
class MemberRepositoryImpl implements MemberRepository {

  private final MemberDao dao;
  private final MemberRoleDao roleDao;

  // Nhận DAO qua constructor
  MemberRepositoryImpl(MemberDao dao, MemberRoleDao roleDao) {
    this.dao = dao;
    this.roleDao = roleDao;
  }

  // Thêm bằng một câu INSERT ON CONFLICT DO NOTHING, true khi đúng một dòng được thêm
  @Override
  public boolean addIfAbsent(Member member) {
    return dao.insertIfAbsent(member.serverId().value(), member.userId().value(), member.joinedAt())
        == 1;
  }

  // Ghi lại role của thành viên bằng xóa rồi chèn lại
  @Override
  public void save(Member member) {
    UUID serverId = member.serverId().value();
    UUID userId = member.userId().value();
    roleDao.deleteAllFor(serverId, userId);
    member.roleIds().forEach(r -> roleDao.insert(serverId, userId, r.value()));
  }

  // Tìm thành viên theo khóa ghép kèm role
  @Override
  public Optional<Member> find(ServerId serverId, UserId userId) {
    return dao.findById(new MemberKey(serverId.value(), userId.value()))
        .map(e -> withRoles(List.of(e)).get(0));
  }

  // Tìm thành viên bằng SELECT FOR UPDATE kèm role đọc sau khi có khóa
  @Override
  public Optional<Member> findForUpdate(ServerId serverId, UserId userId) {
    return dao.findForUpdate(serverId.value(), userId.value())
        .map(e -> withRoles(List.of(e)).get(0));
  }

  // Gỡ thành viên theo khóa ghép
  @Override
  public void remove(ServerId serverId, UserId userId) {
    dao.deleteById(new MemberKey(serverId.value(), userId.value()));
  }

  // Đếm thành viên của server
  @Override
  public long count(ServerId serverId) {
    return dao.countByIdServerId(serverId.value());
  }

  // Phân trang keyset theo user_id tăng dần
  @Override
  public List<Member> page(ServerId serverId, UserId afterUserId, int limit) {
    PageRequest paging = PageRequest.ofSize(limit);
    List<MemberJpaEntity> rows =
        afterUserId == null
            ? dao.findByIdServerIdOrderByIdUserIdAsc(serverId.value(), paging)
            : dao.findByIdServerIdAndIdUserIdGreaterThanOrderByIdUserIdAsc(
                serverId.value(), afterUserId.value(), paging);
    return withRoles(rows);
  }

  // Dựng aggregate từ các dòng thành viên cùng server, nạp role bằng một query
  private List<Member> withRoles(List<MemberJpaEntity> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    UUID serverId = rows.get(0).id.serverId();
    Map<UUID, List<RoleId>> roles =
        roleDao
            .findAllByServerIdAndUserIdIn(serverId, rows.stream().map(e -> e.id.userId()).toList())
            .stream()
            .collect(
                Collectors.groupingBy(
                    e -> e.id.userId(),
                    Collectors.mapping(e -> new RoleId(e.id.roleId()), Collectors.toList())));
    return rows.stream()
        .map(
            e ->
                Member.rehydrate(
                    new ServerId(e.id.serverId()),
                    new UserId(e.id.userId()),
                    e.joinedAt,
                    roles.getOrDefault(e.id.userId(), List.of())))
        .toList();
  }
}

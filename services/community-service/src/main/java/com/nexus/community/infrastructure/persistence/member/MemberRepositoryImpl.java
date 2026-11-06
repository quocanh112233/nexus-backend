package com.nexus.community.infrastructure.persistence.member;

import com.nexus.community.domain.member.Member;
import com.nexus.community.domain.member.MemberRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

// Cài đặt MemberRepository bằng MemberDao
@Repository
class MemberRepositoryImpl implements MemberRepository {

  private final MemberDao dao;

  // Nhận DAO qua constructor
  MemberRepositoryImpl(MemberDao dao) {
    this.dao = dao;
  }

  // Thêm bằng một câu INSERT ON CONFLICT DO NOTHING, true khi đúng một dòng được thêm
  @Override
  public boolean addIfAbsent(Member member) {
    return dao.insertIfAbsent(member.serverId().value(), member.userId().value(), member.joinedAt())
        == 1;
  }

  // Tìm thành viên theo khóa ghép
  @Override
  public Optional<Member> find(ServerId serverId, UserId userId) {
    return dao.findById(new MemberKey(serverId.value(), userId.value())).map(this::toDomain);
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
    return rows.stream().map(this::toDomain).toList();
  }

  // Dựng lại aggregate từ entity qua Member.rehydrate
  private Member toDomain(MemberJpaEntity e) {
    return Member.rehydrate(new ServerId(e.id.serverId()), new UserId(e.id.userId()), e.joinedAt);
  }
}

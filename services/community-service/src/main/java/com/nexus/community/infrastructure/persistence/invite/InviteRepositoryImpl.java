package com.nexus.community.infrastructure.persistence.invite;

import com.nexus.community.domain.invite.Invite;
import com.nexus.community.domain.invite.InviteCode;
import com.nexus.community.domain.invite.InviteRepository;
import com.nexus.community.domain.server.ServerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// Cài đặt InviteRepository bằng InviteDao và InviteMapper
@Repository
class InviteRepositoryImpl implements InviteRepository {

  private final InviteDao dao;
  private final InviteMapper mapper;

  // Nhận DAO và mapper qua constructor
  InviteRepositoryImpl(InviteDao dao, InviteMapper mapper) {
    this.dao = dao;
    this.mapper = mapper;
  }

  // Persist và flush ngay, trùng mã thì vi phạm khóa chính mà không đụng hàng đã có
  @Override
  public void add(Invite invite) {
    InviteJpaEntity entity = mapper.toJpa(invite);
    entity.fresh = true;
    dao.saveAndFlush(entity);
  }

  // Merge và flush ngay; chỉ revoked_at được ghi vì các cột khác không updatable
  @Override
  public void save(Invite invite) {
    dao.saveAndFlush(mapper.toJpa(invite));
  }

  // Tìm lời mời theo mã
  @Override
  public Optional<Invite> findByCode(InviteCode code) {
    return dao.findById(code.value()).map(mapper::toDomain);
  }

  // Liệt kê lời mời của server theo created_at giảm dần
  @Override
  public List<Invite> findAllByServer(ServerId serverId) {
    return dao.findAllByServerIdOrderByCreatedAtDescCodeAsc(serverId.value()).stream()
        .map(mapper::toDomain)
        .toList();
  }

  // Một câu UPDATE có điều kiện, true khi đúng một dòng được tăng
  @Override
  public boolean tryConsume(InviteCode code, Instant now) {
    return dao.tryConsume(code.value(), now) == 1;
  }
}

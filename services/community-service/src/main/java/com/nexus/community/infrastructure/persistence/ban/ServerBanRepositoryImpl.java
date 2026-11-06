package com.nexus.community.infrastructure.persistence.ban;

import com.nexus.community.domain.ban.ServerBan;
import com.nexus.community.domain.ban.ServerBanRepository;
import com.nexus.community.domain.server.ServerId;
import com.nexus.community.domain.shared.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

// Cài đặt ServerBanRepository bằng BanDao
@Repository
class ServerBanRepositoryImpl implements ServerBanRepository {

  private final BanDao dao;

  // Nhận DAO qua constructor
  ServerBanRepositoryImpl(BanDao dao) {
    this.dao = dao;
  }

  // Thêm bằng một câu INSERT ON CONFLICT DO NOTHING, true khi đúng một dòng được thêm
  @Override
  public boolean addIfAbsent(ServerBan ban) {
    return dao.insertIfAbsent(
            ban.serverId().value(),
            ban.userId().value(),
            ban.bannedBy().value(),
            ban.reason(),
            ban.createdAt())
        == 1;
  }

  // Tìm ban theo khóa ghép
  @Override
  public Optional<ServerBan> find(ServerId serverId, UserId userId) {
    return dao.findById(new BanKey(serverId.value(), userId.value()))
        .map(ServerBanRepositoryImpl::toDomain);
  }

  // Xóa ban bằng DELETE native, true khi có dòng bị xóa
  @Override
  public boolean remove(ServerId serverId, UserId userId) {
    return dao.deleteByKey(serverId.value(), userId.value()) == 1;
  }

  // Phân trang keyset theo user_id tăng dần
  @Override
  public List<ServerBan> page(ServerId serverId, UserId afterUserId, int limit) {
    PageRequest paging = PageRequest.ofSize(limit);
    List<BanJpaEntity> rows =
        afterUserId == null
            ? dao.findByIdServerIdOrderByIdUserIdAsc(serverId.value(), paging)
            : dao.findByIdServerIdAndIdUserIdGreaterThanOrderByIdUserIdAsc(
                serverId.value(), afterUserId.value(), paging);
    return rows.stream().map(ServerBanRepositoryImpl::toDomain).toList();
  }

  // Dựng aggregate từ một dòng đã lưu
  private static ServerBan toDomain(BanJpaEntity e) {
    return ServerBan.rehydrate(
        new ServerId(e.id.serverId()),
        new UserId(e.id.userId()),
        new UserId(e.bannedBy),
        e.reason,
        e.createdAt);
  }
}

package com.nexus.messaging.infrastructure.persistence.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

// Cài đặt FriendshipRepository bằng INSERT ... ON CONFLICT DO NOTHING và DELETE native
@Repository
class FriendshipRepositoryImpl implements FriendshipRepository {

  private final FriendshipDao dao;

  // Nhận DAO qua constructor
  FriendshipRepositoryImpl(FriendshipDao dao) {
    this.dao = dao;
  }

  // Chèn quan hệ nếu chưa có, true khi chèn thật
  @Override
  public boolean insertIfAbsent(UserPair pair, Instant createdAt) {
    return dao.insertIfAbsent(pair.low().value(), pair.high().value(), createdAt) > 0;
  }

  // Xóa quan hệ, true khi có dòng bị xóa
  @Override
  public boolean delete(UserPair pair) {
    return dao.deleteOne(pair.low().value(), pair.high().value()) > 0;
  }

  // Hai người có đang là bạn không
  @Override
  public boolean exists(UserPair pair) {
    return dao.existsById(new FriendshipJpaEntity.Key(pair.low().value(), pair.high().value()));
  }

  // Id bạn bè của user
  @Override
  public List<UserId> friendIdsOf(UserId user) {
    return dao.friendIdsOf(user.value()).stream().map(UserId::new).toList();
  }
}

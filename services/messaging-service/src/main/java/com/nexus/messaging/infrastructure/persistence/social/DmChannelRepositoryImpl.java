package com.nexus.messaging.infrastructure.persistence.social;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// Cài đặt DmChannelRepository, chèn bằng INSERT ... ON CONFLICT DO NOTHING
@Repository
class DmChannelRepositoryImpl implements DmChannelRepository {

  private final DmChannelDao dao;

  // Nhận DAO qua constructor
  DmChannelRepositoryImpl(DmChannelDao dao) {
    this.dao = dao;
  }

  // Tìm kênh DM của cặp
  @Override
  public Optional<DmChannel> findByPair(UserPair pair) {
    return dao.findByUserLowIdAndUserHighId(pair.low().value(), pair.high().value())
        .map(this::toDomain);
  }

  // Tìm kênh DM theo id
  @Override
  public Optional<DmChannel> findById(ChannelId id) {
    return dao.findById(id.value()).map(this::toDomain);
  }

  // Chèn kênh nếu cặp chưa có kênh, true khi chèn thật
  @Override
  public boolean insertIfAbsent(DmChannel c) {
    return dao.insertIfAbsent(
            c.id(), c.pair().low().value(), c.pair().high().value(), c.createdAt())
        > 0;
  }

  // Các kênh DM có user tham gia
  @Override
  public List<DmChannel> listOf(UserId user) {
    return dao
        .findByUserLowIdOrUserHighIdOrderByCreatedAtDescIdDesc(user.value(), user.value())
        .stream()
        .map(this::toDomain)
        .toList();
  }

  // Dựng lại kênh từ entity JPA, cặp đi qua UserPair.of để luôn chuẩn hóa
  private DmChannel toDomain(DmChannelJpaEntity e) {
    return DmChannel.rehydrate(
        e.id, UserPair.of(new UserId(e.userLowId), new UserId(e.userHighId)), e.createdAt);
  }
}

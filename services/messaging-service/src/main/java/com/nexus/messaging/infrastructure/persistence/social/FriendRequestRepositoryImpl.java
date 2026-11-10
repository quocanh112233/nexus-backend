package com.nexus.messaging.infrastructure.persistence.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequest;
import com.nexus.messaging.domain.social.FriendRequestExistsException;
import com.nexus.messaging.domain.social.FriendRequestId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import com.nexus.messaging.domain.social.FriendRequestStatus;
import com.nexus.messaging.domain.social.UserPair;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

// Cài đặt FriendRequestRepository, đổi vi phạm index PENDING thành FriendRequestExistsException
@Repository
class FriendRequestRepositoryImpl implements FriendRequestRepository {

  private static final String PENDING_INDEX = "ux_friend_requests_pending";

  private final FriendRequestDao dao;

  // Nhận DAO qua constructor
  FriendRequestRepositoryImpl(FriendRequestDao dao) {
    this.dao = dao;
  }

  // Lưu và flush ngay để ràng buộc nổ tại đây, lời mời mới thì persist (version null)
  @Override
  public FriendRequest save(FriendRequest request) {
    FriendRequestJpaEntity entity = toJpa(request);
    if (!dao.existsById(entity.id)) {
      entity.version = null;
    }
    try {
      return toDomain(dao.saveAndFlush(entity));
    } catch (DataIntegrityViolationException e) {
      if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(PENDING_INDEX)) {
        throw new FriendRequestExistsException("a pending friend request already exists");
      }
      throw e;
    }
  }

  // Tìm lời mời theo id
  @Override
  public Optional<FriendRequest> findById(FriendRequestId id) {
    return dao.findById(id.value()).map(this::toDomain);
  }

  // Tìm lời mời đang chờ của cặp, không phân biệt chiều
  @Override
  public Optional<FriendRequest> findPending(UserPair pair) {
    return dao.findPending(pair.low().value(), pair.high().value()).map(this::toDomain);
  }

  // Lời mời đang chờ gửi tới user
  @Override
  public List<FriendRequest> pendingIncoming(UserId user) {
    return dao
        .findByAddresseeIdAndStatusOrderByCreatedAtDesc(user.value(), FriendRequestStatus.PENDING)
        .stream()
        .map(this::toDomain)
        .toList();
  }

  // Lời mời đang chờ do user gửi
  @Override
  public List<FriendRequest> pendingOutgoing(UserId user) {
    return dao
        .findByRequesterIdAndStatusOrderByCreatedAtDesc(user.value(), FriendRequestStatus.PENDING)
        .stream()
        .map(this::toDomain)
        .toList();
  }

  // Chép aggregate sang entity JPA
  private FriendRequestJpaEntity toJpa(FriendRequest r) {
    FriendRequestJpaEntity e = new FriendRequestJpaEntity();
    e.id = r.id().value();
    e.requesterId = r.requester().value();
    e.addresseeId = r.addressee().value();
    e.status = r.status();
    e.version = r.version();
    e.createdAt = r.createdAt();
    e.respondedAt = r.respondedAt();
    return e;
  }

  // Dựng lại aggregate từ entity JPA, mang theo version đã lưu
  private FriendRequest toDomain(FriendRequestJpaEntity e) {
    return FriendRequest.rehydrate(
        new FriendRequestId(e.id),
        new UserId(e.requesterId),
        new UserId(e.addresseeId),
        e.status,
        e.version,
        e.createdAt,
        e.respondedAt);
  }
}

package com.nexus.messaging.infrastructure.persistence.social;

import com.nexus.messaging.domain.social.FriendRequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data DAO cho bảng friend_requests
interface FriendRequestDao extends JpaRepository<FriendRequestJpaEntity, UUID> {

  // Lời mời PENDING của cặp theo đúng biểu thức của index ux_friend_requests_pending
  @Query(
      value =
          "select * from {h-schema}friend_requests"
              + " where least(requester_id, addressee_id) = :low"
              + " and greatest(requester_id, addressee_id) = :high and status = 'PENDING'",
      nativeQuery = true)
  Optional<FriendRequestJpaEntity> findPending(@Param("low") UUID low, @Param("high") UUID high);

  // Lời mời gửi tới user theo trạng thái, mới nhất trước
  List<FriendRequestJpaEntity> findByAddresseeIdAndStatusOrderByCreatedAtDesc(
      UUID addresseeId, FriendRequestStatus status);

  // Lời mời do user gửi theo trạng thái, mới nhất trước
  List<FriendRequestJpaEntity> findByRequesterIdAndStatusOrderByCreatedAtDesc(
      UUID requesterId, FriendRequestStatus status);
}

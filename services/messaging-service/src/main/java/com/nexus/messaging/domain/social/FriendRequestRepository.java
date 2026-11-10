package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.UserId;
import java.util.List;
import java.util.Optional;

// Cổng lưu trữ lời mời kết bạn, DB bảo đảm mỗi cặp chỉ một lời mời PENDING
public interface FriendRequestRepository {

  // Lưu và trả bản có version mới; trùng PENDING ném FriendRequestExistsException
  FriendRequest save(FriendRequest request);

  // Tìm lời mời theo id
  Optional<FriendRequest> findById(FriendRequestId id);

  // Tìm lời mời đang chờ của cặp, không phân biệt chiều
  Optional<FriendRequest> findPending(UserPair pair);

  // Lời mời đang chờ gửi tới user, mới nhất trước
  List<FriendRequest> pendingIncoming(UserId user);

  // Lời mời đang chờ do user gửi, mới nhất trước
  List<FriendRequest> pendingOutgoing(UserId user);
}

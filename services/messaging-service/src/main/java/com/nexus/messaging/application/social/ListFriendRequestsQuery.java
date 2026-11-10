package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendRequestRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn lời mời kết bạn đang chờ đến và đi của một user
@Service
public class ListFriendRequestsQuery {

  private final FriendRequestRepository requests;

  // Nhận repository lời mời qua constructor
  public ListFriendRequestsQuery(FriendRequestRepository requests) {
    this.requests = requests;
  }

  // Lời mời PENDING gửi tới user, mới nhất trước
  @Transactional(readOnly = true)
  public List<FriendRequestView> incoming(UserId user) {
    return requests.pendingIncoming(user).stream().map(FriendRequestView::from).toList();
  }

  // Lời mời PENDING do user gửi, mới nhất trước
  @Transactional(readOnly = true)
  public List<FriendRequestView> outgoing(UserId user) {
    return requests.pendingOutgoing(user).stream().map(FriendRequestView::from).toList();
  }
}

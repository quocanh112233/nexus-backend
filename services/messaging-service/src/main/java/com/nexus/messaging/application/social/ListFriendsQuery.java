package com.nexus.messaging.application.social;

import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.FriendshipRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Truy vấn danh sách id bạn bè của một user
@Service
public class ListFriendsQuery {

  private final FriendshipRepository friendships;

  // Nhận repository bạn bè qua constructor
  public ListFriendsQuery(FriendshipRepository friendships) {
    this.friendships = friendships;
  }

  // Trả id bạn bè của user
  @Transactional(readOnly = true)
  public List<UUID> execute(UserId user) {
    return friendships.friendIdsOf(user).stream().map(UserId::value).toList();
  }
}

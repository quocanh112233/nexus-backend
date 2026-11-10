package com.nexus.messaging.domain.social;

import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.List;

// Cổng lưu trữ quan hệ bạn bè, các thao tác ghi idempotent và cho biết có thay đổi thật không
public interface FriendshipRepository {

  // Thêm quan hệ nếu chưa có, true khi chèn thật
  boolean insertIfAbsent(UserPair pair, Instant createdAt);

  // Xóa quan hệ, true khi có dòng bị xóa
  boolean delete(UserPair pair);

  // Hai người có đang là bạn không
  boolean exists(UserPair pair);

  // Danh sách id bạn bè của user, bất kể user là low hay high
  List<UserId> friendIdsOf(UserId user);
}

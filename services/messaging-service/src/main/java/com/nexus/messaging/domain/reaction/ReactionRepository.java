package com.nexus.messaging.domain.reaction;

import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// Cổng lưu trữ reaction, cả hai thao tác idempotent và cho biết có thay đổi thật hay không
public interface ReactionRepository {

  // Thêm reaction nếu chưa có, true khi chèn thật
  boolean insertIfAbsent(Reaction reaction);

  // Xóa reaction của user, true khi có dòng bị xóa
  boolean delete(MessageId messageId, UserId userId, Emoji emoji);

  // Tổng hợp reaction của cả trang bằng một truy vấn, tin không có reaction thì vắng mặt trong map
  Map<MessageId, List<ReactionCount>> summarize(Collection<MessageId> messageIds, UserId viewer);
}

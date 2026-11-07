package com.nexus.messaging.infrastructure.persistence.reaction;

import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.Reaction;
import com.nexus.messaging.domain.reaction.ReactionCount;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.UserId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

// Cài đặt ReactionRepository bằng INSERT ... ON CONFLICT DO NOTHING và DELETE native
@Repository
class ReactionRepositoryImpl implements ReactionRepository {

  private final ReactionDao dao;

  // Nhận DAO qua constructor
  ReactionRepositoryImpl(ReactionDao dao) {
    this.dao = dao;
  }

  // Chèn reaction nếu chưa có, true khi chèn thật
  @Override
  public boolean insertIfAbsent(Reaction r) {
    return dao.insertIfAbsent(
            r.messageId().value(), r.userId().value(), r.emoji().value(), r.createdAt())
        > 0;
  }

  // Xóa reaction của user, true khi có dòng bị xóa
  @Override
  public boolean delete(MessageId messageId, UserId userId, Emoji emoji) {
    return dao.deleteOne(messageId.value(), userId.value(), emoji.value()) > 0;
  }

  // Gom reaction của cả trang bằng một truy vấn GROUP BY
  @Override
  public Map<MessageId, List<ReactionCount>> summarize(
      Collection<MessageId> messageIds, UserId viewer) {
    Map<MessageId, List<ReactionCount>> result = new HashMap<>();
    if (messageIds.isEmpty()) {
      return result;
    }
    for (Object[] row :
        dao.summarize(messageIds.stream().map(MessageId::value).toList(), viewer.value())) {
      result
          .computeIfAbsent(new MessageId((UUID) row[0]), k -> new ArrayList<>())
          .add(new ReactionCount((String) row[1], ((Number) row[2]).intValue(), (Boolean) row[3]));
    }
    return result;
  }
}

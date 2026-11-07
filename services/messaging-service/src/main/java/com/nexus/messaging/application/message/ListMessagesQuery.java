package com.nexus.messaging.application.message;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.reaction.ReactionCount;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Use case đọc lịch sử tin theo keyset, kèm tóm tắt reaction của cả trang bằng một truy vấn
@Service
public class ListMessagesQuery {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final ChannelPermissionChecker checker;
  private final MessageRepository messageRepository;
  private final ReactionRepository reactionRepository;

  // Nhận cổng kiểm tra quyền và hai cổng lưu trữ qua constructor
  public ListMessagesQuery(
      ChannelPermissionChecker checker,
      MessageRepository messageRepository,
      ReactionRepository reactionRepository) {
    this.checker = checker;
    this.messageRepository = messageRepository;
    this.reactionRepository = reactionRepository;
  }

  // Validate tham số, kiểm quyền READ_MESSAGE_HISTORY rồi trả trang tin; tin đã xóa thành tombstone
  @Transactional(readOnly = true)
  public List<MessageView> execute(
      ChannelId channelId, UserId viewer, MessageId before, MessageId after, Integer limit) {
    if (before != null && after != null) {
      throw new DomainValidationException("before and after are mutually exclusive");
    }
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new DomainValidationException("limit must be between 1 and " + MAX_LIMIT);
    }
    ChannelAccess access = checker.check(viewer, channelId);
    access.require(ChannelPermission.READ_MESSAGE_HISTORY);
    List<Message> page = messageRepository.findPage(channelId, before, after, size);
    List<MessageId> live =
        page.stream().filter(m -> m.deletedAt() == null).map(Message::id).toList();
    Map<MessageId, List<ReactionCount>> reactions =
        live.isEmpty() ? Map.of() : reactionRepository.summarize(live, viewer);
    return page.stream()
        .map(m -> MessageView.from(m).withReactions(reactions.getOrDefault(m.id(), List.of())))
        .toList();
  }
}

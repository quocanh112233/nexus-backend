package com.nexus.messaging.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.Reaction;
import com.nexus.messaging.domain.reaction.ReactionCount;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra quyền, validate tham số và dựng tombstone của ListMessagesQuery bằng fake repository
class ListMessagesQueryTest {

  private final UUID serverId = UUID.randomUUID();
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId viewer = new UserId(UUID.randomUUID());
  private final FakeMessages messages = new FakeMessages();
  private final FakeReactions reactions = new FakeReactions();
  private ChannelAccess access =
      new ChannelAccess(
          serverId,
          EnumSet.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY));
  private final ChannelPermissionChecker checker = (user, ch) -> access;
  private final ListMessagesQuery query = new ListMessagesQuery(checker, messages, reactions);

  // Kịch bản: không truyền limit
  // Input: before, after, limit đều null
  // Output: repository nhận đúng một limit là 50
  @Test
  void defaultLimitIs50() {
    query.execute(channel, viewer, null, null, null);

    assertThat(messages.limits).containsExactly(50);
  }

  // Kịch bản: truyền cả before lẫn after
  // Input: hai con trỏ cùng khác null
  // Output: DomainValidationException và repository không bị gọi
  @Test
  void rejectsBothCursors() {
    assertThatThrownBy(
            () -> query.execute(channel, viewer, MessageId.newId(), MessageId.newId(), 10))
        .isInstanceOf(DomainValidationException.class);

    assertThat(messages.limits).isEmpty();
  }

  // Kịch bản: limit ngoài khoảng [1, 100]
  // Input: limit 101 và 0
  // Output: cả hai DomainValidationException và repository không bị gọi
  @Test
  void rejectsLimit101() {
    assertThatThrownBy(() -> query.execute(channel, viewer, null, null, 101))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> query.execute(channel, viewer, null, null, 0))
        .isInstanceOf(DomainValidationException.class);

    assertThat(messages.limits).isEmpty();
  }

  // Kịch bản: user thấy kênh nhưng thiếu READ_MESSAGE_HISTORY, và user không thấy kênh
  // Input: quyền chỉ VIEW_CHANNEL rồi quyền rỗng
  // Output: PermissionDeniedException rồi ChannelNotFoundException, repository không bị gọi
  @Test
  void needsReadMessageHistory() {
    access = new ChannelAccess(serverId, EnumSet.of(ChannelPermission.VIEW_CHANNEL));
    assertThatThrownBy(() -> query.execute(channel, viewer, null, null, null))
        .isInstanceOf(PermissionDeniedException.class);

    access = new ChannelAccess(serverId, EnumSet.noneOf(ChannelPermission.class));
    assertThatThrownBy(() -> query.execute(channel, viewer, null, null, null))
        .isInstanceOf(ChannelNotFoundException.class);

    assertThat(messages.limits).isEmpty();
  }

  // Kịch bản: trang có một tin sống và một tin đã xóa mềm
  // Input: tin sống có reaction, tin xóa có replyToId
  // Output: tin xóa là tombstone giữ replyToId, chỉ tra reaction cho tin sống
  @Test
  void deletedMessageIsTombstoneAndSkipsReactionLookup() {
    MessageId liveId = MessageId.newId();
    MessageId goneId = MessageId.newId();
    messages.page = List.of(message(liveId, "hi", null, false), message(goneId, "", liveId, true));
    reactions.result = Map.of(liveId, List.of(new ReactionCount("👍", 2, true)));

    List<MessageView> views = query.execute(channel, viewer, null, null, null);

    assertThat(views.get(0).content()).isEqualTo("hi");
    assertThat(views.get(0).reactions()).containsExactly(new ReactionCount("👍", 2, true));
    assertThat(views.get(1).deleted()).isTrue();
    assertThat(views.get(1).content()).isNull();
    assertThat(views.get(1).replyToId()).isEqualTo(liveId.value());
    assertThat(views.get(1).reactions()).isEmpty();
    assertThat(reactions.asked).containsExactly(liveId);
  }

  // Dựng tin trong kênh test, có thể đã xóa mềm
  private Message message(MessageId id, String content, MessageId replyTo, boolean deleted) {
    Instant at = Instant.parse("2026-11-07T09:00:00Z");
    return Message.rehydrate(
        id,
        channel,
        new UserId(UUID.randomUUID()),
        content,
        replyTo,
        null,
        Set.of(),
        List.of(),
        at,
        null,
        deleted ? at : null,
        0);
  }

  // Repository tin nhắn giả ghi lại limit mỗi lần findPage và trả trang đặt sẵn
  private static final class FakeMessages implements MessageRepository {
    final List<Integer> limits = new ArrayList<>();
    List<Message> page = List.of();

    // Ghi limit và trả trang đặt sẵn
    @Override
    public List<Message> findPage(
        ChannelId channelId, MessageId before, MessageId after, int limit) {
      limits.add(limit);
      return page;
    }

    // Không dùng trong test này
    @Override
    public Message save(Message message) {
      throw new UnsupportedOperationException();
    }

    // Không dùng trong test này
    @Override
    public Optional<Message> findById(MessageId id) {
      return Optional.empty();
    }

    // Không dùng trong test này
    @Override
    public Optional<Message> findByAuthorAndNonce(UserId authorId, Nonce nonce) {
      return Optional.empty();
    }
  }

  // Repository reaction giả ghi lại các id được hỏi và trả kết quả đặt sẵn
  private static final class FakeReactions implements ReactionRepository {
    Collection<MessageId> asked = List.of();
    Map<MessageId, List<ReactionCount>> result = Map.of();

    // Không dùng trong test này
    @Override
    public boolean insertIfAbsent(Reaction reaction) {
      return false;
    }

    // Không dùng trong test này
    @Override
    public boolean delete(MessageId messageId, UserId userId, Emoji emoji) {
      return false;
    }

    // Ghi id được hỏi và trả kết quả đặt sẵn
    @Override
    public Map<MessageId, List<ReactionCount>> summarize(Collection<MessageId> ids, UserId viewer) {
      asked = ids;
      return result;
    }
  }
}

package com.nexus.messaging.application.reaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.reaction.Emoji;
import com.nexus.messaging.domain.reaction.Reaction;
import com.nexus.messaging.domain.reaction.ReactionAdded;
import com.nexus.messaging.domain.reaction.ReactionCount;
import com.nexus.messaging.domain.reaction.ReactionRemoved;
import com.nexus.messaging.domain.reaction.ReactionRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra quyền, IDOR và tính idempotent của AddReactionUseCase và RemoveReactionUseCase
class AddReactionUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private final UUID serverId = UUID.randomUUID();
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId user = new UserId(UUID.randomUUID());
  private final Emoji emoji = new Emoji("👍");
  private final Map<MessageId, Message> byId = new HashMap<>();
  private final Set<String> rows = new HashSet<>();
  private final List<DomainEvent> events = new ArrayList<>();
  private ChannelAccess access =
      new ChannelAccess(
          serverId, Set.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.ADD_REACTIONS));
  private final ChannelPermissionChecker checker = (u, ch) -> access;
  private final DomainEventPublisher publisher = events::add;
  private final AddReactionUseCase add =
      new AddReactionUseCase(
          checker,
          new FakeMessages(),
          new FakeReactions(),
          publisher,
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final RemoveReactionUseCase remove =
      new RemoveReactionUseCase(checker, new FakeMessages(), new FakeReactions(), publisher);

  // Kịch bản: chỉ có VIEW_CHANNEL, thiếu ADD_REACTIONS
  // Input: thêm reaction vào tin hợp lệ
  // Output: PermissionDeniedException, không dòng DB, không event
  @Test
  void deniedWithoutAddReactions() {
    Message m = seed(channel, false);
    access = new ChannelAccess(serverId, Set.of(ChannelPermission.VIEW_CHANNEL));

    assertThatThrownBy(() -> add.execute(channel, m.id(), user, emoji))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(rows).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: thêm cùng reaction hai lần
  // Input: cùng user, tin và emoji gọi add hai lần
  // Output: một dòng DB, đúng một event ReactionAdded
  @Test
  void duplicateAddPublishesOnce() {
    Message m = seed(channel, false);

    add.execute(channel, m.id(), user, emoji);
    add.execute(channel, m.id(), user, emoji);

    assertThat(rows).hasSize(1);
    assertThat(events).containsExactly(new ReactionAdded(m.id(), channel, user, emoji));
  }

  // Kịch bản: thêm reaction vào tin đã xóa mềm
  // Input: tin có deletedAt, user đủ quyền
  // Output: MessageNotFoundException, không dòng DB, không event
  @Test
  void deletedMessageIsNotFound() {
    Message m = seed(channel, true);

    assertThatThrownBy(() -> add.execute(channel, m.id(), user, emoji))
        .isInstanceOf(MessageNotFoundException.class);

    assertThat(rows).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: messageId thuộc kênh khác kênh trên URL
  // Input: tin của kênh khác, user đủ quyền ở kênh URL
  // Output: MessageNotFoundException, không dòng DB, không event
  @Test
  void messageFromOtherChannelIsNotFound() {
    Message m = seed(new ChannelId(UUID.randomUUID()), false);

    assertThatThrownBy(() -> add.execute(channel, m.id(), user, emoji))
        .isInstanceOf(MessageNotFoundException.class);

    assertThat(rows).isEmpty();
    assertThat(events).isEmpty();
  }

  // Kịch bản: gỡ reaction chưa từng thêm, chỉ có VIEW_CHANNEL
  // Input: remove trên tin hợp lệ khi chưa có dòng nào
  // Output: không lỗi, không event; sau add rồi remove thì có đúng một ReactionRemoved
  @Test
  void removeWithoutExistingReactionPublishesNothing() {
    Message m = seed(channel, false);
    access = new ChannelAccess(serverId, Set.of(ChannelPermission.VIEW_CHANNEL));

    remove.execute(channel, m.id(), user, emoji);

    assertThat(events).isEmpty();
    rows.add(key(m.id(), user, emoji));
    remove.execute(channel, m.id(), user, emoji);
    remove.execute(channel, m.id(), user, emoji);
    assertThat(rows).isEmpty();
    assertThat(events).containsExactly(new ReactionRemoved(m.id(), channel, user, emoji));
  }

  // Khóa dòng reaction dạng chuỗi
  private static String key(MessageId m, UserId u, Emoji e) {
    return m.value() + "|" + u.value() + "|" + e.value();
  }

  // Lưu sẵn một tin ở kênh cho trước, có thể đã xóa mềm
  private Message seed(ChannelId channelId, boolean deleted) {
    Message m =
        Message.rehydrate(
            MessageId.newId(),
            channelId,
            new UserId(UUID.randomUUID()),
            "old",
            null,
            null,
            Set.of(),
            List.of(),
            Instant.parse("2026-11-07T09:00:00Z"),
            null,
            deleted ? Instant.parse("2026-11-07T09:30:00Z") : null,
            0);
    byId.put(m.id(), m);
    return m;
  }

  // Repository tin nhắn trong bộ nhớ dùng chung bản đồ của test
  private final class FakeMessages implements MessageRepository {

    // Lưu tin vào bản đồ
    @Override
    public Message save(Message message) {
      byId.put(message.id(), message);
      return message;
    }

    // Tìm theo id
    @Override
    public Optional<Message> findById(MessageId id) {
      return Optional.ofNullable(byId.get(id));
    }

    // Không dùng trong test này
    @Override
    public List<Message> findPage(
        ChannelId channelId, MessageId before, MessageId after, int limit) {
      return List.of();
    }

    // Tìm theo tác giả và nonce
    @Override
    public Optional<Message> findByAuthorAndNonce(UserId authorId, Nonce nonce) {
      return Optional.empty();
    }
  }

  // Repository reaction trong bộ nhớ dùng chung tập dòng của test
  private final class FakeReactions implements ReactionRepository {

    // Thêm dòng, true khi chưa có
    @Override
    public boolean insertIfAbsent(Reaction r) {
      return rows.add(key(r.messageId(), r.userId(), r.emoji()));
    }

    // Không dùng trong test này
    @Override
    public Map<MessageId, List<ReactionCount>> summarize(Collection<MessageId> ids, UserId viewer) {
      return Map.of();
    }

    // Xóa dòng, true khi có xóa
    @Override
    public boolean delete(MessageId m, UserId u, Emoji e) {
      return rows.remove(key(m, u, e));
    }
  }
}

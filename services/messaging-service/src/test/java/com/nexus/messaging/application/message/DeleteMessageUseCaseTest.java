package com.nexus.messaging.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageDeleted;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra quyền và xóa mềm của DeleteMessageUseCase bằng fake repository và checker
class DeleteMessageUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private final UUID serverId = UUID.randomUUID();
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId author = new UserId(UUID.randomUUID());
  private final UserId other = new UserId(UUID.randomUUID());
  private final Map<MessageId, Message> byId = new HashMap<>();
  private final List<DomainEvent> events = new ArrayList<>();
  private ChannelAccess access = granted();
  private final ChannelPermissionChecker checker = (user, ch) -> access;
  private final DomainEventPublisher publisher = events::add;
  private final DeleteMessageUseCase useCase =
      new DeleteMessageUseCase(
          checker, new FakeMessages(), publisher, Clock.fixed(NOW, ZoneOffset.UTC));

  // Kịch bản: tác giả xóa tin của mình không cần MANAGE_MESSAGES
  // Input: tin có mention, tác giả gọi xóa
  // Output: deletedAt đặt, content rỗng, mention rỗng, event MessageDeleted deletedBy là tác giả
  @Test
  void authorDeletes() {
    Message m = seed(channel, false);

    useCase.execute(channel, m.id(), author);

    Message saved = byId.get(m.id());
    assertThat(saved.deletedAt()).isEqualTo(NOW);
    assertThat(saved.content()).isEmpty();
    assertThat(saved.mentions()).isEmpty();
    assertThat(events).containsExactly(new MessageDeleted(m.id(), channel, serverId, author));
  }

  // Kịch bản: người có MANAGE_MESSAGES xóa tin của người khác
  // Input: quyền MANAGE_MESSAGES, người gọi không phải tác giả
  // Output: tin bị xóa mềm, event deletedBy là người kiểm duyệt
  @Test
  void moderatorWithManageMessagesDeletesOthers() {
    Message m = seed(channel, false);
    access = granted(ChannelPermission.MANAGE_MESSAGES);

    useCase.execute(channel, m.id(), other);

    assertThat(byId.get(m.id()).deletedAt()).isEqualTo(NOW);
    assertThat(events).containsExactly(new MessageDeleted(m.id(), channel, serverId, other));
  }

  // Kịch bản: thành viên thường xóa tin người khác
  // Input: chỉ VIEW_CHANNEL, người gọi không phải tác giả
  // Output: PermissionDeniedException, tin còn nguyên, không event
  @Test
  void memberWithoutManageMessagesCannotDeleteOthers() {
    Message m = seed(channel, false);

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), other))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(byId.get(m.id()).deletedAt()).isNull();
    assertThat(events).isEmpty();
  }

  // Kịch bản: xóa lần hai cùng một tin
  // Input: tin đã xóa mềm sẵn, tác giả gọi xóa
  // Output: không lỗi, deletedAt giữ nguyên, không có event
  @Test
  void secondDeleteIsNoopWithoutEvent() {
    Message m = seed(channel, true);

    useCase.execute(channel, m.id(), author);

    assertThat(byId.get(m.id()).deletedAt()).isEqualTo(Instant.parse("2026-11-07T09:30:00Z"));
    assertThat(events).isEmpty();
  }

  // Kịch bản: messageId thuộc kênh khác kênh trên URL
  // Input: tin của kênh khác, người gọi là tác giả có MANAGE_MESSAGES
  // Output: MessageNotFoundException, tin không đổi, không event
  @Test
  void messageFromOtherChannelIsNotFound() {
    Message m = seed(new ChannelId(UUID.randomUUID()), false);
    access = granted(ChannelPermission.MANAGE_MESSAGES);

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), author))
        .isInstanceOf(MessageNotFoundException.class);

    assertThat(byId.get(m.id()).deletedAt()).isNull();
    assertThat(events).isEmpty();
  }

  // Dựng quyền gồm VIEW_CHANNEL và các quyền bổ sung
  private ChannelAccess granted(ChannelPermission... extra) {
    Set<ChannelPermission> set = EnumSet.of(ChannelPermission.VIEW_CHANNEL);
    set.addAll(List.of(extra));
    return new ChannelAccess(serverId, set);
  }

  // Lưu sẵn một tin của tác giả ở kênh cho trước, có thể đã xóa mềm
  private Message seed(ChannelId channelId, boolean deleted) {
    Message m =
        Message.rehydrate(
            MessageId.newId(),
            channelId,
            author,
            deleted ? "" : "old",
            null,
            null,
            deleted ? Set.of() : Set.of(new UserId(UUID.randomUUID())),
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
}

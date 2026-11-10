package com.nexus.messaging.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.MessageNotFoundException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageEdited;
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

// Kiểm tra thứ tự kiểm tra và quy tắc của EditMessageUseCase bằng fake repository và checker
class EditMessageUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private final UUID serverId = UUID.randomUUID();
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId author = new UserId(UUID.randomUUID());
  private final FakeMessages messages = new FakeMessages();
  private final List<DomainEvent> events = new ArrayList<>();
  private ChannelAccess access = granted();
  private final ChannelPermissionChecker checker = (user, ch) -> access;
  private final DomainEventPublisher publisher = events::add;
  private final EditMessageUseCase useCase =
      new EditMessageUseCase(checker, messages, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

  // Kịch bản: tác giả sửa tin có version 3
  // Input: nội dung mới kèm một mention
  // Output: view có nội dung mới, editedAt đúng giờ, event MessageEdited version 4 kèm serverId
  @Test
  void authorEditsAndEventCarriesNewVersion() {
    Message m = messages.seed(channel, author, "old", 3, false);
    UUID mentioned = UUID.randomUUID();

    MessageView view = useCase.execute(channel, m.id(), author, "new <@" + mentioned + ">");

    assertThat(view.content()).isEqualTo("new <@" + mentioned + ">");
    assertThat(view.editedAt()).isEqualTo(NOW);
    assertThat(events)
        .containsExactly(
            new MessageEdited(
                m.id(),
                channel,
                serverId,
                "new <@" + mentioned + ">",
                Set.of(new UserId(mentioned)),
                NOW,
                4));
  }

  // Kịch bản: người khác tác giả có MANAGE_MESSAGES sửa tin
  // Input: quyền VIEW_CHANNEL và MANAGE_MESSAGES, editor không phải tác giả
  // Output: PermissionDeniedException, nội dung không đổi, không event
  @Test
  void nonAuthorEditingIsForbiddenEvenWithManageMessages() {
    Message m = messages.seed(channel, author, "old", 0, false);
    access = granted(ChannelPermission.MANAGE_MESSAGES);

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), new UserId(UUID.randomUUID()), "x"))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(messages.byId.get(m.id()).content()).isEqualTo("old");
    assertThat(events).isEmpty();
  }

  // Kịch bản: tác giả sửa tin trong DM khi không còn quyền gửi (đã hủy kết bạn)
  // Input: ChannelAccess(null, {VIEW_CHANNEL}), editor là tác giả
  // Output: PermissionDeniedException, nội dung không đổi, không event
  @Test
  void dmAuthorWithoutSendMessagesCannotEdit() {
    Message m = messages.seed(channel, author, "old", 0, false);
    access = new ChannelAccess(null, EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), author, "new"))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(messages.byId.get(m.id()).content()).isEqualTo("old");
    assertThat(events).isEmpty();
  }

  // Kịch bản: sửa tin đã xóa mềm
  // Input: tin có deletedAt
  // Output: MessageNotFoundException, không event
  @Test
  void editingDeletedMessageIsNotFound() {
    Message m = messages.seed(channel, author, "", 0, true);

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), author, "x"))
        .isInstanceOf(MessageNotFoundException.class);

    assertThat(events).isEmpty();
  }

  // Kịch bản: sửa với nội dung giống hệt
  // Input: tin có editedAt cũ, nội dung mới trùng nội dung hiện tại
  // Output: view nguyên trạng (editedAt cũ), không lưu lại, không event
  @Test
  void sameContentProducesNoEvent() {
    Message m = messages.seed(channel, author, "same", 2, false);
    messages.saves = 0;

    MessageView view = useCase.execute(channel, m.id(), author, "same");

    assertThat(view.editedAt()).isNull();
    assertThat(view.version()).isEqualTo(2);
    assertThat(messages.saves).isZero();
    assertThat(events).isEmpty();
  }

  // Kịch bản: messageId thuộc kênh khác kênh trên URL
  // Input: tin của kênh khác, user có đủ quyền ở kênh URL
  // Output: MessageNotFoundException, tin không đổi, không event
  @Test
  void messageFromOtherChannelIsNotFound() {
    Message other = messages.seed(new ChannelId(UUID.randomUUID()), author, "old", 0, false);

    assertThatThrownBy(() -> useCase.execute(channel, other.id(), author, "x"))
        .isInstanceOf(MessageNotFoundException.class);

    assertThat(messages.byId.get(other.id()).content()).isEqualTo("old");
    assertThat(events).isEmpty();
  }

  // Kịch bản: user không thấy kênh
  // Input: tập quyền rỗng, tin tồn tại
  // Output: ChannelNotFoundException, không event
  @Test
  void hiddenChannelIsNotFound() {
    Message m = messages.seed(channel, author, "old", 0, false);
    access = new ChannelAccess(serverId, EnumSet.noneOf(ChannelPermission.class));

    assertThatThrownBy(() -> useCase.execute(channel, m.id(), author, "x"))
        .isInstanceOf(ChannelNotFoundException.class);

    assertThat(events).isEmpty();
  }

  // Kịch bản: sửa nội dung có nhiều mention
  // Input: "<@u3> <@u1> <@u2> <@u1>"
  // Output: mentionUserIds giữ thứ tự xuất hiện u3, u1, u2
  @Test
  void editKeepsMentionOrderOfAppearance() {
    Message m = messages.seed(channel, author, "old", 0, false);
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    UUID u3 = UUID.randomUUID();

    MessageView view =
        useCase.execute(
            channel, m.id(), author, "<@" + u3 + "> <@" + u1 + "> <@" + u2 + "> <@" + u1 + ">");

    assertThat(view.mentionUserIds()).containsExactly(u3, u1, u2);
  }

  // Dựng quyền gồm VIEW_CHANNEL và các quyền bổ sung
  private ChannelAccess granted(ChannelPermission... extra) {
    Set<ChannelPermission> set = EnumSet.of(ChannelPermission.VIEW_CHANNEL);
    set.addAll(List.of(extra));
    return new ChannelAccess(serverId, set);
  }

  // Repository tin nhắn trong bộ nhớ, đếm số lần lưu
  private static final class FakeMessages implements MessageRepository {
    final Map<MessageId, Message> byId = new HashMap<>();
    int saves;

    // Lưu sẵn một tin với tác giả, nội dung, version cho trước, có thể đã xóa mềm
    Message seed(ChannelId channelId, UserId authorId, String content, long version, boolean del) {
      Message m =
          Message.rehydrate(
              MessageId.newId(),
              channelId,
              authorId,
              content,
              null,
              null,
              Set.of(),
              List.of(),
              Instant.parse("2026-11-07T09:00:00Z"),
              null,
              del ? Instant.parse("2026-11-07T09:30:00Z") : null,
              version);
      byId.put(m.id(), m);
      return m;
    }

    // Lưu tin vào bản đồ và đếm
    @Override
    public Message save(Message message) {
      saves++;
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

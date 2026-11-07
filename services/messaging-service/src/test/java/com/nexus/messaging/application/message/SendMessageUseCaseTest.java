package com.nexus.messaging.application.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nexus.messaging.application.port.AttachmentVerifier;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.port.DomainEventPublisher;
import com.nexus.messaging.application.port.UploadSigner;
import com.nexus.messaging.application.shared.ChannelNotFoundException;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import com.nexus.messaging.application.shared.PermissionDeniedException;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageCreated;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.DomainValidationException;
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

// Kiểm tra thứ tự và quy tắc của SendMessageUseCase bằng fake repository và checker
class SendMessageUseCaseTest {

  private final UUID serverId = UUID.randomUUID();
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UserId author = new UserId(UUID.randomUUID());
  private final FakeMessages messages = new FakeMessages();
  private final FakeChannelRefs refs = new FakeChannelRefs();
  private final List<DomainEvent> events = new ArrayList<>();
  private ChannelAccess access = granted(ChannelPermission.SEND_MESSAGES);
  private final ChannelPermissionChecker checker = (user, ch) -> access;
  private final DomainEventPublisher publisher = events::add;
  private final SendMessageUseCase useCase =
      new SendMessageUseCase(
          checker,
          messages,
          refs,
          mock(UploadSigner.class),
          mock(AttachmentVerifier.class),
          publisher,
          Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));

  // Kịch bản: user thấy kênh nhưng thiếu SEND_MESSAGES
  // Input: quyền chỉ có VIEW_CHANNEL
  // Output: PermissionDeniedException, không lưu tin, không event, không channel_ref
  @Test
  void deniedWithoutSendMessages() {
    access = new ChannelAccess(serverId, EnumSet.of(ChannelPermission.VIEW_CHANNEL));

    assertThatThrownBy(() -> useCase.execute(command("hi", null, null)))
        .isInstanceOf(PermissionDeniedException.class);

    assertThat(messages.byId).isEmpty();
    assertThat(events).isEmpty();
    assertThat(refs.saved).isEmpty();
  }

  // Kịch bản: user không thấy kênh
  // Input: tập quyền rỗng
  // Output: ChannelNotFoundException (không lộ sự tồn tại), không lưu gì
  @Test
  void hiddenChannelIsNotFound() {
    access = new ChannelAccess(serverId, EnumSet.noneOf(ChannelPermission.class));

    assertThatThrownBy(() -> useCase.execute(command("hi", null, null)))
        .isInstanceOf(ChannelNotFoundException.class);

    assertThat(messages.byId).isEmpty();
  }

  // Kịch bản: gửi lại cùng nonce của cùng tác giả
  // Input: hai lần execute với nonce "n-1"
  // Output: lần 1 created=true, lần 2 created=false cùng id, chỉ 1 tin và 1 event
  @Test
  void duplicateNonceReturnsExistingWithoutNewEvent() {
    SendMessageUseCase.SendResult first = useCase.execute(command("hi", new Nonce("n-1"), null));
    SendMessageUseCase.SendResult second = useCase.execute(command("hi", new Nonce("n-1"), null));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.message().id()).isEqualTo(first.message().id());
    assertThat(messages.byId).hasSize(1);
    assertThat(events).hasSize(1);
  }

  // Kịch bản: reply tới tin của kênh khác
  // Input: replyToId trỏ tin thuộc kênh khác
  // Output: DomainValidationException, không lưu tin mới
  @Test
  void replyToOtherChannelIsValidationError() {
    Message other = messages.seed(new ChannelId(UUID.randomUUID()), false);

    assertThatThrownBy(() -> useCase.execute(command("hi", null, other.id())))
        .isInstanceOf(DomainValidationException.class);

    assertThat(messages.byId).hasSize(1);
  }

  // Kịch bản: reply tới tin không tồn tại
  // Input: replyToId ngẫu nhiên
  // Output: DomainValidationException
  @Test
  void replyToMissingMessageIsValidationError() {
    assertThatThrownBy(() -> useCase.execute(command("hi", null, MessageId.newId())))
        .isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: reply tới tin đã xóa mềm cùng kênh
  // Input: replyToId trỏ tin có deletedAt
  // Output: tạo được, replyToId được giữ
  @Test
  void replyToDeletedMessageIsAllowed() {
    Message deleted = messages.seed(channel, true);

    SendMessageUseCase.SendResult result = useCase.execute(command("hi", null, deleted.id()));

    assertThat(result.created()).isTrue();
    assertThat(result.message().replyToId()).isEqualTo(deleted.id().value());
  }

  // Kịch bản: Community không trả lời
  // Input: checker ném DependencyUnavailableException
  // Output: lỗi lan ra nguyên vẹn, không lưu tin, không event
  @Test
  void dependencyUnavailablePropagatesAndNothingSaved() {
    SendMessageUseCase down =
        new SendMessageUseCase(
            (user, ch) -> {
              throw new DependencyUnavailableException("down", new RuntimeException());
            },
            messages,
            refs,
            mock(UploadSigner.class),
            mock(AttachmentVerifier.class),
            publisher,
            Clock.systemUTC());

    assertThatThrownBy(() -> down.execute(command("hi", null, null)))
        .isInstanceOf(DependencyUnavailableException.class);

    assertThat(messages.byId).isEmpty();
    assertThat(events).isEmpty();
    assertThat(refs.saved).isEmpty();
  }

  // Kịch bản: lần gửi đầu vào kênh ghi nhớ kênh server
  // Input: execute một tin hợp lệ
  // Output: channel_refs nhận (channel, serverId) và event MessageCreated mang serverId
  @Test
  void savesChannelRefOnFirstSend() {
    useCase.execute(command("hi", null, null));

    assertThat(refs.saved).containsEntry(channel, serverId);
    assertThat(events).singleElement().isInstanceOf(MessageCreated.class);
    assertThat(((MessageCreated) events.get(0)).serverId()).isEqualTo(serverId);
  }

  // Kịch bản: mention trong nội dung được parse và gộp trùng
  // Input: "<@u1> <@u1> <@u2>"
  // Output: view có đúng 2 mentionUserIds
  @Test
  void mentionsAreParsedAndDeduped() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();

    SendMessageUseCase.SendResult result =
        useCase.execute(command("<@" + u1 + "> <@" + u1 + "> <@" + u2 + ">", null, null));

    assertThat(result.message().mentionUserIds()).containsExactlyInAnyOrder(u1, u2);
  }

  // Kịch bản: gửi nội dung có nhiều mention
  // Input: "<@u3> <@u1> <@u2> <@u1>"
  // Output: mentionUserIds giữ thứ tự xuất hiện u3, u1, u2
  @Test
  void sendKeepsMentionOrderOfAppearance() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    UUID u3 = UUID.randomUUID();

    SendMessageUseCase.SendResult result =
        useCase.execute(
            command("<@" + u3 + "> <@" + u1 + "> <@" + u2 + "> <@" + u1 + ">", null, null));

    assertThat(result.message().mentionUserIds()).containsExactly(u3, u1, u2);
  }

  // Kịch bản: nội dung toàn khoảng trắng và không attachment
  // Input: "   "
  // Output: DomainValidationException, không lưu
  @Test
  void blankContentIsValidationError() {
    assertThatThrownBy(() -> useCase.execute(command("   ", null, null)))
        .isInstanceOf(DomainValidationException.class);

    assertThat(messages.byId).isEmpty();
  }

  // Dựng quyền gồm VIEW_CHANNEL và các quyền bổ sung
  private ChannelAccess granted(ChannelPermission... extra) {
    Set<ChannelPermission> set = EnumSet.of(ChannelPermission.VIEW_CHANNEL);
    set.addAll(List.of(extra));
    return new ChannelAccess(serverId, set);
  }

  // Dựng lệnh gửi không attachment
  private SendMessageCommand command(String content, Nonce nonce, MessageId replyTo) {
    return new SendMessageCommand(channel, author, content, nonce, replyTo, List.of());
  }

  // Repository tin nhắn trong bộ nhớ
  private static final class FakeMessages implements MessageRepository {
    final Map<MessageId, Message> byId = new HashMap<>();

    // Lưu sẵn một tin của kênh cho trước, có thể đã xóa mềm
    Message seed(ChannelId channelId, boolean deleted) {
      Message m =
          Message.rehydrate(
              MessageId.newId(),
              channelId,
              new UserId(UUID.randomUUID()),
              deleted ? "" : "old",
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
      return byId.values().stream()
          .filter(m -> m.authorId().equals(authorId) && nonce.equals(m.nonce()))
          .findFirst();
    }
  }

  // Repository channel_refs trong bộ nhớ
  private static final class FakeChannelRefs implements ChannelRefRepository {
    final Map<ChannelId, UUID> saved = new HashMap<>();

    // Ghi nhớ kênh server nếu chưa có
    @Override
    public void upsertServerChannel(ChannelId channelId, UUID serverId) {
      saved.putIfAbsent(channelId, serverId);
    }
  }
}

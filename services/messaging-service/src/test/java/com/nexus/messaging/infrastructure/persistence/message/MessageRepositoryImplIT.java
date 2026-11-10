package com.nexus.messaging.infrastructure.persistence.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.nexus.common.messaging.OutboxWriter;
import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.message.SendMessageCommand;
import com.nexus.messaging.application.message.SendMessageUseCase;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.message.Attachment;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageContent;
import com.nexus.messaging.domain.message.MessageId;
import com.nexus.messaging.domain.message.MessageRepository;
import com.nexus.messaging.domain.message.Nonce;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.ChannelPermission;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// Kiểm tra MessageRepositoryImpl trên Postgres thật và tính nguyên tử giữa tin nhắn với outbox
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class MessageRepositoryImplIT extends MessagingIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  @Autowired private MessageRepository repository;
  @Autowired private SendMessageUseCase sendMessage;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private ChannelPermissionChecker checker;
  @MockitoSpyBean private OutboxWriter outboxWriter;

  // Kịch bản: lưu tin có reply, nonce, hai mention và hai attachment rồi đọc lại
  // Input: Message.create đủ trường, save, findById
  // Output: tin đọc lại bằng nhau từng trường, version 0, mention và attachment đủ
  @Test
  void savesMessageWithMentionsAndAttachments() {
    UserId author = new UserId(UUID.randomUUID());
    UserId m1 = new UserId(UUID.randomUUID());
    UserId m2 = new UserId(UUID.randomUUID());
    MessageId reply = MessageId.newId();
    Message created =
        Message.create(
            MessageId.newId(),
            new ChannelId(UUID.randomUUID()),
            UUID.randomUUID(),
            author,
            new MessageContent("xin chào 👍"),
            reply,
            new Nonce("n-" + UUID.randomUUID()),
            Set.of(m1, m2),
            List.of(file("a.png"), file("b.png")),
            NOW);

    repository.save(created);
    Message loaded = repository.findById(created.id()).orElseThrow();

    assertThat(loaded.channelId()).isEqualTo(created.channelId());
    assertThat(loaded.authorId()).isEqualTo(author);
    assertThat(loaded.content()).isEqualTo("xin chào 👍");
    assertThat(loaded.replyToId()).isEqualTo(reply);
    assertThat(loaded.nonce()).isEqualTo(created.nonce());
    assertThat(loaded.mentions()).containsExactlyInAnyOrder(m1, m2);
    assertThat(loaded.attachments()).containsExactlyInAnyOrderElementsOf(created.attachments());
    assertThat(loaded.createdAt()).isEqualTo(NOW);
    assertThat(loaded.editedAt()).isNull();
    assertThat(loaded.deletedAt()).isNull();
    assertThat(loaded.version()).isZero();
  }

  // Kịch bản: tra theo nonce chỉ khớp đúng tác giả
  // Input: A lưu tin nonce "n", tra (A, n) và (B, n)
  // Output: A thấy tin, B không thấy gì
  @Test
  void findByAuthorAndNonceFindsOnlySameAuthor() {
    UserId a = new UserId(UUID.randomUUID());
    UserId b = new UserId(UUID.randomUUID());
    Nonce nonce = new Nonce("n-" + UUID.randomUUID());
    Message created =
        Message.create(
            MessageId.newId(),
            new ChannelId(UUID.randomUUID()),
            UUID.randomUUID(),
            a,
            new MessageContent("hi"),
            null,
            nonce,
            Set.of(),
            List.of(),
            NOW);
    repository.save(created);

    assertThat(repository.findByAuthorAndNonce(a, nonce)).map(Message::id).contains(created.id());
    assertThat(repository.findByAuthorAndNonce(b, nonce)).isEmpty();
  }

  // Kịch bản: use case lỗi ngay sau khi ghi outbox
  // Input: OutboxWriter thật ghi xong rồi ném IllegalStateException
  // Output: không còn dòng nào ở messages, message_mentions, channel_refs lẫn outbox_event
  @Test
  void ruleOutboxAndMessageShareTransaction() {
    UserId author = new UserId(UUID.randomUUID());
    ChannelId channel = new ChannelId(UUID.randomUUID());
    UUID mentioned = UUID.randomUUID();
    when(checker.check(any(), any()))
        .thenReturn(
            new ChannelAccess(
                UUID.randomUUID(),
                EnumSet.of(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES)));
    doAnswer(
            inv -> {
              inv.callRealMethod();
              throw new IllegalStateException("boom");
            })
        .when(outboxWriter)
        .append(any());

    assertThatThrownBy(
            () ->
                sendMessage.execute(
                    new SendMessageCommand(
                        channel, author, "hi <@" + mentioned + ">", null, null, List.of())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("messages where author_id = ?", author.value())).isZero();
    assertThat(count("message_mentions where user_id = ?", mentioned)).isZero();
    assertThat(count("channel_refs where channel_id = ?", channel.value())).isZero();
    assertThat(count("outbox_event where payload->>'authorId' = ?", author.value().toString()))
        .isZero();
  }

  // Đếm dòng của bảng trong schema messaging theo điều kiện một tham số
  private int count(String fromWhere, Object arg) {
    return jdbc.queryForObject("select count(*) from messaging." + fromWhere, Integer.class, arg);
  }

  // Tạo attachment mẫu với tên file cho trước
  private Attachment file(String name) {
    return new Attachment(
        UUID.randomUUID(), "pid-" + name, "https://x/" + name, "image/png", 5, name);
  }
}

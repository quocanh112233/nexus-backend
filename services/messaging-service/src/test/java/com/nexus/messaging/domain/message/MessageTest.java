package com.nexus.messaging.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra bất biến và sự kiện của aggregate Message
class MessageTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private final ChannelId channel = new ChannelId(UUID.randomUUID());
  private final UUID serverId = UUID.randomUUID();
  private final UserId author = new UserId(UUID.randomUUID());

  // Kịch bản: nội dung toàn khoảng trắng chỉ hợp lệ khi kèm attachment
  // Input: "   " không attachment, rồi "   " với một attachment
  // Output: lần đầu ném DomainValidationException, lần sau tạo được
  @Test
  void blankContentAllowedOnlyWithAttachment() {
    assertThatThrownBy(() -> create("   ", List.of()))
        .isInstanceOf(DomainValidationException.class);

    assertThat(create("   ", List.of(attachment())).attachments()).hasSize(1);
  }

  // Kịch bản: quá 5 attachment
  // Input: 6 attachment
  // Output: DomainValidationException
  @Test
  void rejectsSixAttachments() {
    List<Attachment> six = java.util.stream.Stream.generate(this::attachment).limit(6).toList();

    assertThatThrownBy(() -> create("hi", six)).isInstanceOf(DomainValidationException.class);
  }

  // Kịch bản: tạo tin mới ghi nhận sự kiện MessageCreated
  // Input: tin có mention, reply và nonce
  // Output: pullEvents trả đúng một MessageCreated đủ trường, lần gọi sau rỗng
  @Test
  void createRecordsMessageCreatedEvent() {
    MessageId id = MessageId.newId();
    MessageId reply = MessageId.newId();
    UserId mentioned = new UserId(UUID.randomUUID());

    Message m =
        Message.create(
            id,
            channel,
            serverId,
            author,
            new MessageContent("hi"),
            reply,
            new Nonce("n-1"),
            Set.of(mentioned),
            List.of(),
            NOW);

    assertThat(m.pullEvents())
        .containsExactly(
            new MessageCreated(
                id,
                channel,
                serverId,
                author,
                "hi",
                reply,
                List.of(),
                Set.of(mentioned),
                NOW,
                new Nonce("n-1")));
    assertThat(m.pullEvents()).isEmpty();
    assertThat(m.version()).isZero();
    assertThat(m.createdAt()).isEqualTo(NOW);
    assertThat(m.editedAt()).isNull();
    assertThat(m.deletedAt()).isNull();
  }

  // Kịch bản: nonce quá 64 ký tự hoặc rỗng
  // Input: nonce 65 ký tự và nonce rỗng
  // Output: cả hai ném DomainValidationException
  @Test
  void nonceMustBe1To64Chars() {
    assertThatThrownBy(() -> new Nonce("x".repeat(65)))
        .isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> new Nonce("")).isInstanceOf(DomainValidationException.class);
    assertThat(new Nonce("x".repeat(64)).value()).hasSize(64);
  }

  // Tạo tin với nội dung và attachment cho trước, không reply, không nonce
  private Message create(String content, List<Attachment> attachments) {
    return Message.create(
        MessageId.newId(),
        channel,
        serverId,
        author,
        new MessageContent(content),
        null,
        null,
        Set.of(),
        attachments,
        NOW);
  }

  // Tạo một attachment mẫu
  private Attachment attachment() {
    return new Attachment(UUID.randomUUID(), "pid", "https://x/y.png", "image/png", 10, "y.png");
  }
}

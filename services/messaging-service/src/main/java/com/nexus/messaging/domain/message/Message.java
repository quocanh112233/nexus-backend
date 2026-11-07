package com.nexus.messaging.domain.message;

import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.DomainEvent;
import com.nexus.messaging.domain.shared.DomainValidationException;
import com.nexus.messaging.domain.shared.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Aggregate tin nhắn trong kênh và phát sự kiện miền
public final class Message {

  private static final int MAX_ATTACHMENTS = 5;

  private final MessageId id;
  private final ChannelId channelId;
  private final UserId authorId;
  private String content;
  private final MessageId replyToId;
  private final Nonce nonce;
  private Set<UserId> mentions;
  private List<Attachment> attachments;
  private final Instant createdAt;
  private Instant editedAt;
  private Instant deletedAt;
  private final long version;
  private final List<DomainEvent> events = new ArrayList<>();

  // Khởi tạo nội bộ, chỉ gọi qua create hoặc rehydrate
  private Message(
      MessageId id,
      ChannelId channelId,
      UserId authorId,
      String content,
      MessageId replyToId,
      Nonce nonce,
      Set<UserId> mentions,
      List<Attachment> attachments,
      Instant createdAt,
      Instant editedAt,
      Instant deletedAt,
      long version) {
    this.id = id;
    this.channelId = channelId;
    this.authorId = authorId;
    this.content = content;
    this.replyToId = replyToId;
    this.nonce = nonce;
    this.mentions = Collections.unmodifiableSet(new LinkedHashSet<>(mentions));
    this.attachments = List.copyOf(attachments);
    this.createdAt = createdAt;
    this.editedAt = editedAt;
    this.deletedAt = deletedAt;
    this.version = version;
  }

  // Tạo tin mới và phát MessageCreated; serverId chỉ dùng cho sự kiện, không lưu vào tin
  public static Message create(
      MessageId id,
      ChannelId channelId,
      UUID serverId,
      UserId authorId,
      MessageContent content,
      MessageId replyToId,
      Nonce nonce,
      Set<UserId> mentions,
      List<Attachment> attachments,
      Instant now) {
    if (attachments.size() > MAX_ATTACHMENTS) {
      throw new DomainValidationException("at most 5 attachments are allowed");
    }
    if (content.value().isBlank() && attachments.isEmpty()) {
      throw new DomainValidationException("content must not be blank without attachments");
    }
    Message message =
        new Message(
            id,
            channelId,
            authorId,
            content.value(),
            replyToId,
            nonce,
            mentions,
            attachments,
            now,
            null,
            null,
            0);
    message.events.add(
        new MessageCreated(
            id,
            channelId,
            serverId,
            authorId,
            content.value(),
            replyToId,
            message.attachments,
            message.mentions,
            now,
            nonce));
    return message;
  }

  // Dựng lại tin từ lưu trữ, không phát sự kiện; content có thể rỗng khi tin đã xóa
  public static Message rehydrate(
      MessageId id,
      ChannelId channelId,
      UserId authorId,
      String content,
      MessageId replyToId,
      Nonce nonce,
      Set<UserId> mentions,
      List<Attachment> attachments,
      Instant createdAt,
      Instant editedAt,
      Instant deletedAt,
      long version) {
    return new Message(
        id,
        channelId,
        authorId,
        content,
        replyToId,
        nonce,
        mentions,
        attachments,
        createdAt,
        editedAt,
        deletedAt,
        version);
  }

  // Sửa nội dung và mention, phát MessageEdited với version sau lưu; nội dung không đổi thì bỏ qua
  public void edit(MessageContent newContent, Set<UserId> newMentions, UUID serverId, Instant now) {
    if (newContent.value().equals(content)) {
      return;
    }
    if (newContent.value().isBlank() && attachments.isEmpty()) {
      throw new DomainValidationException("content must not be blank without attachments");
    }
    this.content = newContent.value();
    this.mentions = Collections.unmodifiableSet(new LinkedHashSet<>(newMentions));
    this.editedAt = now;
    events.add(new MessageEdited(id, channelId, serverId, content, mentions, now, version + 1));
  }

  // Xóa mềm: xóa nội dung, mention, attachment và phát MessageDeleted; đã xóa thì bỏ qua
  public void delete(UserId by, UUID serverId, Instant now) {
    if (deletedAt != null) {
      return;
    }
    this.content = "";
    this.mentions = Set.of();
    this.attachments = List.of();
    this.deletedAt = now;
    events.add(new MessageDeleted(id, channelId, serverId, by));
  }

  // Lấy và xóa các sự kiện chưa phát
  public List<DomainEvent> pullEvents() {
    List<DomainEvent> pulled = List.copyOf(events);
    events.clear();
    return pulled;
  }

  // Định danh tin
  public MessageId id() {
    return id;
  }

  // Kênh chứa tin
  public ChannelId channelId() {
    return channelId;
  }

  // Tác giả
  public UserId authorId() {
    return authorId;
  }

  // Nội dung, rỗng khi tin đã xóa
  public String content() {
    return content;
  }

  // Tin được trả lời, có thể null
  public MessageId replyToId() {
    return replyToId;
  }

  // Nonce idempotency, có thể null
  public Nonce nonce() {
    return nonce;
  }

  // Người được nhắc
  public Set<UserId> mentions() {
    return mentions;
  }

  // Tệp đính kèm
  public List<Attachment> attachments() {
    return attachments;
  }

  // Thời điểm tạo
  public Instant createdAt() {
    return createdAt;
  }

  // Thời điểm sửa gần nhất, null nếu chưa sửa
  public Instant editedAt() {
    return editedAt;
  }

  // Thời điểm xóa mềm, null nếu chưa xóa
  public Instant deletedAt() {
    return deletedAt;
  }

  // Phiên bản cho khóa lạc quan
  public long version() {
    return version;
  }
}

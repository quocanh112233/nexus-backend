package com.nexus.messaging.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.messaging.MessagingIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// Kiểm tra schema Flyway của messaging: bảng, index và ràng buộc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class SchemaIT extends MessagingIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  // Kịch bản: Flyway migrate V1, V2 vào schema messaging
  // Input: Postgres trống, spring.flyway.schemas=messaging
  // Output: đủ 7 bảng của P4 và 3 bảng social trong information_schema.tables
  @Test
  void flywayCreatesAllTablesInMessagingSchema() {
    var tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'messaging'",
            String.class);

    assertThat(tables)
        .contains(
            "channel_refs",
            "messages",
            "message_attachments",
            "message_mentions",
            "reactions",
            "outbox_event",
            "processed_event",
            "friend_requests",
            "friendships",
            "dm_channels");
  }

  // Kịch bản: chống trùng lời mời chờ phải nằm ở index unique một phần theo cặp không thứ tự
  // Input: pg_indexes.indexdef của ux_friend_requests_pending
  // Output: UNIQUE, có LEAST, GREATEST và điều kiện status = 'PENDING'
  @Test
  void pendingFriendRequestIndexIsPartialUnique() {
    String def =
        jdbc.queryForObject(
            "select indexdef from pg_indexes"
                + " where schemaname = 'messaging' and indexname = 'ux_friend_requests_pending'",
            String.class);

    assertThat(def)
        .contains("UNIQUE", "LEAST", "GREATEST")
        .contains("WHERE (status = 'PENDING'::text)");
  }

  // Kịch bản: cặp bạn bè và DM chỉ lưu theo thứ tự low nhỏ hơn high
  // Input: chèn friendships và dm_channels với low lớn hơn high
  // Output: cả hai bị từ chối với DataIntegrityViolationException
  @Test
  void pairTablesRejectUnorderedPair() {
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000001");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into messaging.friendships (user_low_id, user_high_id, created_at)"
                        + " values (?, ?, now())",
                    high,
                    low))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into messaging.dm_channels (id, user_low_id, user_high_id, created_at)"
                        + " values (?, ?, ?, now())",
                    UUID.randomUUID(),
                    high,
                    low))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // Kịch bản: phân trang keyset đi từ tin mới nhất nên index phải giảm dần theo id
  // Input: pg_indexes.indexdef của messages_channel_id_desc
  // Output: định nghĩa chứa "id DESC"
  @Test
  void messageIdIndexIsDescending() {
    String def =
        jdbc.queryForObject(
            "select indexdef from pg_indexes"
                + " where schemaname = 'messaging' and indexname = 'messages_channel_id_desc'",
            String.class);

    assertThat(def).contains("id DESC");
  }

  // Kịch bản: gửi lại cùng nonce của cùng tác giả (idempotency)
  // Input: hai dòng messages cùng (author_id, nonce)
  // Output: dòng thứ hai bị từ chối với DataIntegrityViolationException
  @Test
  void sameNonceSameAuthorIsRejected() {
    UUID author = UUID.randomUUID();
    insertMessage(author, "n-1");

    assertThatThrownBy(() -> insertMessage(author, "n-1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // Kịch bản: tin không gửi nonce không được đụng nhau
  // Input: hai dòng messages cùng author_id, nonce null
  // Output: cả hai chèn được
  @Test
  void nullNoncesDoNotCollide() {
    UUID author = UUID.randomUUID();

    insertMessage(author, null);
    insertMessage(author, null);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.messages where author_id = ?",
                Integer.class,
                author))
        .isEqualTo(2);
  }

  // Kịch bản: xóa cứng tin nhắn kéo theo reaction và mention
  // Input: một tin có một reaction và một mention rồi delete tin đó
  // Output: reactions và message_mentions của tin đó không còn dòng nào
  @Test
  void deletingMessageCascadesReactionsAndMentions() {
    UUID author = UUID.randomUUID();
    UUID id = insertMessage(author, null);
    jdbc.update(
        "insert into messaging.reactions (message_id, user_id, emoji, created_at) values (?, ?, 'x', now())",
        id,
        author);
    jdbc.update(
        "insert into messaging.message_mentions (message_id, user_id) values (?, ?)", id, author);

    jdbc.update("delete from messaging.messages where id = ?", id);

    assertThat(count("reactions", id)).isZero();
    assertThat(count("message_mentions", id)).isZero();
  }

  // Chèn một tin nhắn tối thiểu rồi trả id
  private UUID insertMessage(UUID author, String nonce) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into messaging.messages (id, channel_id, author_id, content, nonce, created_at)"
            + " values (?, ?, ?, 'hi', ?, now())",
        id,
        UUID.randomUUID(),
        author,
        nonce);
    return id;
  }

  // Đếm dòng của bảng con theo message_id
  private int count(String table, UUID messageId) {
    return jdbc.queryForObject(
        "select count(*) from messaging." + table + " where message_id = ?",
        Integer.class,
        messageId);
  }
}

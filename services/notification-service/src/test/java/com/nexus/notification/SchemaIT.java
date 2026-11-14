package com.nexus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

// Kiểm tra migration V1 tạo đủ bảng và ràng buộc của schema notification
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class SchemaIT extends NotificationIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  // Kịch bản: Flyway chạy V1 trên Postgres sạch
  // Input: khởi động context
  // Output: schema notification có đủ 7 bảng
  @Test
  void flywayCreatesAllTables() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'notification'",
            String.class);

    assertThat(tables)
        .contains(
            "channel_activity",
            "server_members",
            "dm_participants",
            "read_states",
            "mentions",
            "outbox_event",
            "processed_event");
  }

  // Kịch bản: mention_count không được âm
  // Input: chèn read_states với mention_count = -1
  // Output: ném DataIntegrityViolationException
  @Test
  void mentionCountCannotGoNegative() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into notification.read_states"
                        + "(user_id, channel_id, mention_count, updated_at)"
                        + " values (?, ?, -1, now())",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // Kịch bản: cùng một tin mention cùng một user hai lần
  // Input: chèn hai dòng mentions trùng (message_id, user_id)
  // Output: lần hai ném DataIntegrityViolationException
  @Test
  void mentionPrimaryKeyRejectsDuplicate() {
    UUID message = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String sql =
        "insert into notification.mentions(message_id, user_id, channel_id, author_id, created_at)"
            + " values (?, ?, ?, ?, now())";
    jdbc.update(sql, message, user, UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> jdbc.update(sql, message, user, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}

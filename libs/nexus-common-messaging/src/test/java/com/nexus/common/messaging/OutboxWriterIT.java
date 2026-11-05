package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.testsupport.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm tra thư viện outbox hoạt động trong app ở package khác với Postgres thật
@SpringBootTest(classes = ConsumerApp.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class OutboxWriterIT extends PostgresIntegrationTest {

  @Autowired private OutboxWriter writer;
  @Autowired private OutboxEventDao dao;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;

  // Tạo bảng outbox_event bằng đúng SQL mẫu mà service sẽ chép vào migration
  @BeforeAll
  static void createTable(@Autowired JdbcTemplate jdbc) throws Exception {
    var sql = new ClassPathResource("db/outbox/V0__outbox_template.sql");
    jdbc.execute(new String(sql.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  // Kịch bản: service ở package khác ghi một event outbox
  // Input: OutboxMessage routingKey user.registered, payload userId
  // Output: có bean dao và writer, 1 dòng id UUIDv7, headers {}, chưa publish
  @Test
  void appendStoresRowWithPayload() {
    UUID userId = UUID.randomUUID();
    var message =
        new OutboxMessage(
            "User",
            userId,
            "UserRegistered",
            1,
            "user.registered",
            Map.of("userId", userId.toString()));

    tx.executeWithoutResult(status -> writer.append(message));

    assertThat(dao.count()).isEqualTo(1);
    var row =
        jdbc.queryForMap(
            "select id, routing_key, payload->>'userId' as user_id, headers::text as headers,"
                + " published_at, attempts from outbox_event");
    assertThat(row.get("routing_key")).isEqualTo("user.registered");
    assertThat(row.get("user_id")).isEqualTo(userId.toString());
    assertThat(row.get("headers")).isEqualTo("{}");
    assertThat(row.get("published_at")).isNull();
    assertThat(row.get("attempts")).isEqualTo(0);
    assertThat(((UUID) row.get("id")).version()).isEqualTo(7);
  }
}

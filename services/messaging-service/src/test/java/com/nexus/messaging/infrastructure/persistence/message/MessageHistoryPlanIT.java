package com.nexus.messaging.infrastructure.persistence.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.MessagingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// Kiểm tra câu SQL keyset của MessageDao dùng index (channel_id, id DESC) trên dữ liệu lớn
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class MessageHistoryPlanIT extends MessagingIntegrationTest {

  private final UUID channelA = UUID.randomUUID();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;

  // Kịch bản: 100 kênh xen kẽ, mỗi kênh 2000 tin (A chiếm 1%), ANALYZE rồi EXPLAIN ba câu keyset
  // Input: câu latest, before, after của kênh A lấy từ hằng số MessageDao, giao dịch rollback
  // Output: plan có Index Scan trên messages_channel_id_desc và không có Seq Scan
  @Test
  @Transactional
  void keysetQueryUsesIndexScan() {
    jdbc.update(
        "insert into messaging.messages (id, channel_id, author_id, content, created_at)"
            + " select (lpad(to_hex(1700000000000 + g), 12, '0') || '7'"
            + "   || substr(md5(g::text || c::text), 1, 3) || '8'"
            + "   || substr(md5(g::text || c::text), 4, 15))::uuid,"
            + "   c, gen_random_uuid(), 'x', now()"
            + " from generate_series(1, 2000) g,"
            + "   (select ?::uuid c union all"
            + "    select md5(?::text || i)::uuid from generate_series(1, 99) i) v",
        channelA,
        channelA);
    jdbc.execute("analyze messaging.messages");
    UUID middle =
        jdbc.queryForObject(
            "select id from messaging.messages where channel_id = ? order by id offset 1000 limit 1",
            UUID.class,
            channelA);

    for (String sql : List.of(MessageDao.LATEST_SQL, MessageDao.BEFORE_SQL, MessageDao.AFTER_SQL)) {
      String plan =
          String.join(
              "\n",
              named.queryForList(
                  "explain " + sql.replace("{h-schema}", "messaging."),
                  Map.of("channelId", channelA, "before", middle, "after", middle, "limit", 50),
                  String.class));

      assertThat(plan)
          .as(plan)
          .containsPattern("Index (Only )?Scan.* using messages_channel_id_desc");
      assertThat(plan).as(plan).doesNotContain("Seq Scan");
    }
  }
}

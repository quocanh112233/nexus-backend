package com.nexus.common.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

// Chặn xử lý trùng event bằng bảng processed_event, chạy chung transaction của handler
public class InboxGuard {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String sql;

  // Dựng guard dùng schema mặc định của kết nối
  public InboxGuard(JdbcTemplate jdbc, Clock clock) {
    this(jdbc, clock, null);
  }

  // Dựng guard cho schema chỉ định, từ chối tên schema không hợp lệ
  public InboxGuard(JdbcTemplate jdbc, Clock clock, String schema) {
    if (schema != null && !schema.isBlank() && !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Schema không hợp lệ: " + schema);
    }
    this.jdbc = jdbc;
    this.clock = clock;
    this.sql =
        "insert into "
            + (schema == null || schema.isBlank() ? "" : schema + ".")
            + "processed_event (event_id, consumer, processed_at) values (?,?,?)"
            + " on conflict do nothing";
  }

  // True nếu đây là lần đầu consumer nhận eventId này
  public boolean firstDelivery(UUID eventId, String consumer) {
    int rows = jdbc.update(sql, eventId, consumer, Timestamp.from(clock.instant()));
    return rows == 1;
  }
}

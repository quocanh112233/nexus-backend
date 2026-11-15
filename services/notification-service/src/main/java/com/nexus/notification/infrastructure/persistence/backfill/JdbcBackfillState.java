package com.nexus.notification.infrastructure.persistence.backfill;

import com.nexus.notification.application.port.BackfillState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// Cài đặt BackfillState trên bảng backfill_state bằng JdbcTemplate, dùng chung transaction JPA
@Repository
class JdbcBackfillState implements BackfillState {

  private final JdbcTemplate jdbc;

  // Nhận JdbcTemplate qua constructor
  JdbcBackfillState(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // Kiểm dòng đánh dấu bằng EXISTS
  @Override
  public boolean isDone(String name) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from notification.backfill_state where name = ?)",
            Boolean.class,
            name));
  }

  // Ghi dòng đánh dấu với thời điểm hiện tại của DB
  @Override
  public void markDone(String name) {
    jdbc.update(
        "insert into notification.backfill_state (name, completed_at) values (?, now())"
            + " on conflict do nothing",
        name);
  }
}

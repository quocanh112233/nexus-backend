package com.nexus.common.messaging;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.Map;

// Ghi event vào outbox_event trong transaction của use case gọi nó, không tự mở transaction
public class OutboxWriter {

  private final OutboxEventDao dao;
  private final Clock clock;

  // Tạo writer với DAO outbox và đồng hồ hệ thống
  public OutboxWriter(OutboxEventDao dao, Clock clock) {
    this.dao = dao;
    this.clock = clock;
  }

  // Lưu một dòng outbox với id UUIDv7, headers rỗng, chưa publish
  public void append(OutboxMessage message) {
    dao.save(
        new OutboxEventJpaEntity(
            UuidCreator.getTimeOrderedEpoch(), message, Map.of(), clock.instant()));
  }
}

package com.nexus.common.messaging;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.Map;

// Ghi event vào outbox_event trong transaction của use case gọi nó, không tự mở transaction
public class OutboxWriter {

  private final OutboxEventDao dao;
  private final Clock clock;
  private final TraceparentSupplier traceparent;

  // Tạo writer không gắn traceparent
  public OutboxWriter(OutboxEventDao dao, Clock clock) {
    this(dao, clock, () -> null);
  }

  // Tạo writer với DAO outbox, đồng hồ và nguồn traceparent của span hiện tại
  public OutboxWriter(OutboxEventDao dao, Clock clock, TraceparentSupplier traceparent) {
    this.dao = dao;
    this.clock = clock;
    this.traceparent = traceparent;
  }

  // Lưu một dòng outbox với id UUIDv7, headers chứa traceparent nếu có span, chưa publish
  public void append(OutboxMessage message) {
    String tp = traceparent.current();
    Map<String, Object> headers = tp == null ? Map.of() : Map.of("traceparent", tp);
    dao.save(
        new OutboxEventJpaEntity(
            UuidCreator.getTimeOrderedEpoch(), message, headers, clock.instant()));
  }
}

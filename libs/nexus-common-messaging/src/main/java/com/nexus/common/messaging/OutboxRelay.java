package com.nexus.common.messaging;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// Đọc dòng outbox chưa gửi theo thứ tự id và phát lên RabbitMQ, chờ confirm rồi mới đánh dấu đã gửi
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxEventDao dao;
  private final RabbitTemplate rabbit;
  private final JsonMapper mapper;
  private final RelayProperties props;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final String producer;

  // Tạo relay với DAO, template AMQP, mapper JSON, cấu hình, đồng hồ, transaction và tên producer
  public OutboxRelay(
      OutboxEventDao dao,
      RabbitTemplate rabbit,
      JsonMapper mapper,
      RelayProperties props,
      Clock clock,
      TransactionTemplate tx,
      String producer) {
    this.dao = dao;
    this.rabbit = rabbit;
    this.mapper = mapper;
    this.props = props;
    this.clock = clock;
    this.tx = tx;
    this.producer = producer;
  }

  // Chạy định kỳ một lượt relay, nuốt lỗi để lượt sau thử lại
  @Scheduled(
      fixedDelayString = "${nexus.messaging.relay.interval:500ms}",
      initialDelayString = "${nexus.messaging.relay.interval:500ms}")
  void tick() {
    try {
      relayOnce();
    } catch (RuntimeException e) {
      LOG.warn("Relay outbox lỗi, thử lại lượt sau", e);
    }
  }

  // Khóa một batch dòng chưa gửi, gửi lần lượt, dừng ở dòng lỗi đầu tiên; trả số dòng đã gửi
  public int relayOnce() {
    Integer sent =
        tx.execute(
            status -> {
              List<OutboxEventJpaEntity> rows = dao.lockUnpublishedBatch(props.batchSize());
              int count = 0;
              for (OutboxEventJpaEntity row : rows) {
                try {
                  publish(row);
                  row.markPublished(clock.instant());
                  count++;
                } catch (RuntimeException e) {
                  LOG.warn("Gửi dòng outbox {} lỗi", row.getId(), e);
                  row.recordFailure();
                  break;
                }
              }
              return count;
            });
    return sent == null ? 0 : sent;
  }

  // Xóa dòng đã gửi quá thời hạn giữ, trả số dòng đã xóa
  @Scheduled(cron = "0 0 3 * * *")
  public int purgePublished() {
    Integer deleted =
        tx.execute(status -> dao.deletePublishedBefore(clock.instant().minus(props.retention())));
    return deleted == null ? 0 : deleted;
  }

  // Dựng envelope, gửi trên một channel và chờ broker confirm
  private void publish(OutboxEventJpaEntity row) {
    var envelope =
        new EventEnvelope(
            row.getId(),
            row.getEventType(),
            row.getEventVersion(),
            row.getCreatedAt(),
            producer,
            row.getAggregateType(),
            row.getAggregateId(),
            row.getPayload());
    Message message =
        MessageBuilder.withBody(mapper.writeValueAsBytes(envelope))
            .setMessageId(row.getId().toString())
            .setContentType("application/json")
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .copyHeaders(row.getHeaders())
            .build();
    rabbit.invoke(
        ops -> {
          ops.send(props.exchange(), row.getRoutingKey(), message);
          ops.waitForConfirmsOrDie(props.confirmTimeout().toMillis());
          return null;
        });
  }
}

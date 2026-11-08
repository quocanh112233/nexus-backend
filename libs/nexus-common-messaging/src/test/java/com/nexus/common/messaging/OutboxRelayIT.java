package com.nexus.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.consumerapp.ConsumerApp;
import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra OutboxRelay với Postgres và RabbitMQ thật
@SpringBootTest(
    classes = ConsumerApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.application.name=it-relay",
      "nexus.messaging.relay.enabled=true",
      "nexus.messaging.relay.exchange=test.events",
      "nexus.messaging.relay.interval=1h",
      "nexus.messaging.relay.confirm-timeout=2s",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true",
      "spring.rabbitmq.template.mandatory=true",
      "spring.rabbitmq.connection-timeout=2s",
      "spring.rabbitmq.channel-rpc-timeout=2s"
    })
class OutboxRelayIT extends PostgresIntegrationTest {

  private static final String QUEUE = "it.relay";

  @Autowired private OutboxRelay relay;
  @Autowired private OutboxWriter writer;
  @Autowired private OutboxEventDao dao;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private JsonMapper mapper;
  @Autowired private RelayProperties props;

  // Đẩy cấu hình kết nối RabbitMQ của container vào Spring
  @DynamicPropertySource
  static void rabbit(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
  }

  // Tạo lại bảng outbox_event bằng đúng SQL mẫu, vì Postgres dùng chung với OutboxWriterIT
  @BeforeAll
  static void createTable(@Autowired JdbcTemplate jdbc) throws Exception {
    jdbc.execute("drop table if exists outbox_event");
    var sql = new ClassPathResource("db/outbox/V0__outbox_template.sql");
    jdbc.execute(new String(sql.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  // Xóa bảng để IT khác dùng chung Postgres tự tạo lại
  @AfterAll
  static void dropTable(@Autowired JdbcTemplate jdbc) {
    jdbc.execute("drop table if exists outbox_event");
  }

  // Dọn bảng, tạo lại queue rỗng và bind # vào exchange
  @BeforeEach
  void reset() {
    jdbc.execute("delete from outbox_event");
    admin.declareExchange(new TopicExchange("test.events", true, false));
    admin.deleteQueue(QUEUE);
    admin.declareQueue(new Queue(QUEUE));
    admin.declareBinding(binding());
  }

  // Bảo đảm broker không bị bỏ lại trạng thái pause nếu test lỗi giữa chừng
  @AfterEach
  void ensureUnpaused() {
    try {
      RabbitMqContainer.unpause();
    } catch (RuntimeException ignored) {
    }
  }

  // Binding # từ test.events vào queue it.relay
  private static Binding binding() {
    return BindingBuilder.bind(new Queue(QUEUE))
        .to(new TopicExchange("test.events", true, false))
        .with("#");
  }

  // Ghi một dòng outbox trong transaction riêng
  private void append(int i) {
    var id = UUID.randomUUID();
    tx.executeWithoutResult(
        s ->
            writer.append(
                new OutboxMessage(
                    "User", id, "UserRegistered", 1, "user.registered", Map.of("n", i))));
  }

  // Kịch bản: relay phát 3 dòng chưa gửi
  // Input: 3 dòng outbox, broker chạy, bind # vào queue it.relay
  // Output: relayOnce()=3, message đúng thứ tự id, persistent, envelope đủ, dòng có published_at
  @Test
  void relayPublishesPendingRowsInOrderAndMarksPublished() throws Exception {
    for (int i = 0; i < 3; i++) {
      append(i);
    }
    List<UUID> ids = jdbc.queryForList("select id from outbox_event order by id", UUID.class);

    assertThat(relay.relayOnce()).isEqualTo(3);

    for (UUID id : ids) {
      Message m = rabbit.receive(QUEUE, 5000);
      assertThat(m).isNotNull();
      assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
      assertThat(m.getMessageProperties().getReceivedDeliveryMode())
          .isEqualTo(MessageDeliveryMode.PERSISTENT);
      var envelope = mapper.readValue(m.getBody(), EventEnvelope.class);
      assertThat(envelope.eventId()).isEqualTo(id);
      assertThat(envelope.eventType()).isEqualTo("UserRegistered");
      assertThat(envelope.producer()).isEqualTo("it-relay");
      assertThat(envelope.payload()).containsKey("n");
    }
    assertThat(dao.countByPublishedAtIsNull()).isZero();
  }

  // Kịch bản: broker chết giữa chừng rồi sống lại
  // Input: pause broker, 1 dòng outbox, relayOnce(), unpause, relayOnce()
  // Output: lần 1 trả 0 và dòng còn pending với attempts>=1; lần 2 trả 1
  @Test
  void brokerDownKeepsRowsPendingThenRecovers() {
    RabbitMqContainer.pause();
    append(1);

    assertThat(relay.relayOnce()).isZero();

    var row = jdbc.queryForMap("select published_at, attempts from outbox_event");
    assertThat(row.get("published_at")).isNull();
    assertThat((Integer) row.get("attempts")).isGreaterThanOrEqualTo(1);

    RabbitMqContainer.unpause();

    assertThat(relay.relayOnce()).isEqualTo(1);
  }

  // Kịch bản: hai relay chạy song song không gửi trùng dòng
  // Input: 200 dòng outbox, hai thread mỗi thread một OutboxRelay lặp relayOnce()
  // Output: queue đúng 200 message với 200 messageId khác nhau
  @Test
  void twoRelaysNeverPublishSameRowTwice() throws Exception {
    tx.executeWithoutResult(
        s -> {
          for (int i = 0; i < 200; i++) {
            writer.append(
                new OutboxMessage(
                    "User", UUID.randomUUID(), "UserRegistered", 1, "user.registered", Map.of()));
          }
        });

    var a = CompletableFuture.runAsync(this::drain);
    var b = CompletableFuture.runAsync(this::drain);
    CompletableFuture.allOf(a, b).get();

    List<String> messageIds = new ArrayList<>();
    Message m;
    while ((m = rabbit.receive(QUEUE, 1000)) != null) {
      messageIds.add(m.getMessageProperties().getMessageId());
    }
    Set<String> distinct = new HashSet<>(messageIds);
    assertThat(messageIds).hasSize(200);
    assertThat(distinct).hasSize(200);
  }

  // Một relay riêng lặp relayOnce() tới khi không còn dòng pending
  private void drain() {
    var own = new OutboxRelay(dao, rabbit, mapper, props, Clock.systemUTC(), tx, "it-relay");
    while (dao.countByPublishedAtIsNull() > 0) {
      own.relayOnce();
    }
  }

  // Kịch bản: message không route được vẫn coi là đã gửi
  // Input: xóa binding của it.relay, 1 dòng outbox, relayOnce()
  // Output: relayOnce()=1 và dòng có published_at
  @Test
  void unroutableMessageIsStillMarkedPublished() {
    admin.removeBinding(binding());
    append(1);

    assertThat(relay.relayOnce()).isEqualTo(1);
    assertThat(dao.countByPublishedAtIsNull()).isZero();
  }

  // Kịch bản: purge chỉ xóa dòng đã gửi quá 7 ngày
  // Input: 1 dòng gửi 8 ngày trước, 1 dòng gửi hôm qua, 1 dòng chưa gửi
  // Output: purgePublished()=1 và còn lại 2 dòng
  @Test
  void purgeDeletesOnlyOldPublishedRows() {
    for (int i = 0; i < 3; i++) {
      append(i);
    }
    List<UUID> ids = jdbc.queryForList("select id from outbox_event order by id", UUID.class);
    jdbc.update(
        "update outbox_event set published_at = now() - interval '8 days' where id = ?",
        ids.get(0));
    jdbc.update(
        "update outbox_event set published_at = now() - interval '1 day' where id = ?", ids.get(1));

    assertThat(relay.purgePublished()).isEqualTo(1);
    assertThat(dao.count()).isEqualTo(2);
  }
}

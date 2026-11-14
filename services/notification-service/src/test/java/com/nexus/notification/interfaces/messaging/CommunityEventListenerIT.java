package com.nexus.notification.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.testsupport.RabbitMqContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra listener community.events với RabbitMQ và Postgres thật: projection, idempotent, DLQ
@SpringBootTest(properties = "management.server.port=0")
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class CommunityEventListenerIT extends NotificationIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String QUEUE = "notification.community.events";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;

  // Trỏ broker tới container với vhost "/"
  @DynamicPropertySource
  static void broker(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Dọn queue chính và DLQ để mỗi test bắt đầu từ trạng thái sạch
  @BeforeEach
  void purge() {
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
  }

  // Kịch bản: kênh được tạo rồi thành viên vào server
  // Input: ChannelCreated rồi MemberJoined cùng server
  // Output: có dòng channel_activity của kênh và dòng server_members của user
  @Test
  void joinedThenMessageFlowBuildsMembership() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID joined = UUID.randomUUID();
    inOrder(
        () -> {
          publish("channel.created", UUID.randomUUID(), "ChannelCreated", ids(server, channel));
          publish("member.joined", joined, "MemberJoined", member(server, user));
        });

    awaitProcessed(joined);

    assertThat(count("channel_activity", "channel_id", channel)).isEqualTo(1);
    assertThat(count("server_members", "user_id", user)).isEqualTo(1);
  }

  // Kịch bản: cùng eventId giao hai lần
  // Input: MemberJoined cùng eventId hai lần rồi một event đánh dấu
  // Output: processed_event đúng 1 dòng và server_members đúng 1 dòng
  @Test
  void redeliveryIsIdempotent() {
    UUID server = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID marker = UUID.randomUUID();
    inOrder(
        () -> {
          publish("member.joined", eventId, "MemberJoined", member(server, user));
          publish("member.joined", eventId, "MemberJoined", member(server, user));
          publish("member.left", marker, "MemberLeft", member(server, UUID.randomUUID()));
        });

    awaitProcessed(marker);

    assertThat(processedCount(eventId)).isEqualTo(1);
    assertThat(count("server_members", "user_id", user)).isEqualTo(1);
  }

  // Kịch bản: ChannelDeleted đến trước ChannelCreated
  // Input: ChannelDeleted rồi ChannelCreated cùng channelId
  // Output: deleted_at của channel_activity vẫn khác null
  @Test
  void deleteBeforeCreateKeepsTombstone() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID created = UUID.randomUUID();
    inOrder(
        () -> {
          publish("channel.deleted", UUID.randomUUID(), "ChannelDeleted", ids(server, channel));
          publish("channel.created", created, "ChannelCreated", ids(server, channel));
        });

    awaitProcessed(created);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from notification.channel_activity"
                    + " where channel_id = ? and deleted_at is not null",
                Integer.class,
                channel))
        .isEqualTo(1);
  }

  // Kịch bản: user bị kick khỏi server
  // Input: seed read_states, mentions của hai user trong kênh của server, rồi MemberKicked
  // Output: hết dòng của user bị kick ở cả hai bảng, dòng của user kia còn nguyên
  @Test
  void kickedPurgesReadStatesAndMentions() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID kicked = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    jdbc.update(
        "insert into notification.channel_activity (channel_id, server_id) values (?, ?)",
        channel,
        server);
    for (UUID u : new UUID[] {kicked, other}) {
      jdbc.update(
          "insert into notification.read_states (user_id, channel_id, updated_at)"
              + " values (?, ?, now())",
          u,
          channel);
      jdbc.update(
          "insert into notification.mentions"
              + " (message_id, user_id, channel_id, server_id, author_id, created_at)"
              + " values (?, ?, ?, ?, ?, now())",
          UUID.randomUUID(),
          u,
          channel,
          server,
          UUID.randomUUID());
    }
    UUID eventId = UUID.randomUUID();

    publish("member.kicked", eventId, "MemberKicked", member(server, kicked));

    awaitProcessed(eventId);
    assertThat(count("read_states", "user_id", kicked)).isZero();
    assertThat(count("mentions", "user_id", kicked)).isZero();
    assertThat(count("read_states", "user_id", other)).isEqualTo(1);
    assertThat(count("mentions", "user_id", other)).isEqualTo(1);
  }

  // Kịch bản: payload thiếu channelId
  // Input: ChannelCreated chỉ có serverId
  // Output: trong 5 giây DLQ có 1 message, queue chính rỗng, không có dòng processed_event
  @Test
  void poisonPayloadGoesToDlq() {
    UUID eventId = UUID.randomUUID();

    publish("channel.created", eventId, "ChannelCreated", Map.of("serverId", UUID.randomUUID()));

    await().atMost(Duration.ofSeconds(5)).until(() -> messages(QUEUE + ".dlq") == 1);
    assertThat(messages(QUEUE)).isZero();
    assertThat(processedCount(eventId)).isZero();
  }

  // Kịch bản: eventType lạ nhưng routing key được bind
  // Input: eventType Whatever với routing key member.joined, payload không có userId
  // Output: queue và DLQ rỗng, processed_event 1 dòng, không có dòng server_members
  @Test
  void unknownEventTypeIsAckedAndIgnored() {
    UUID eventId = UUID.randomUUID();
    UUID server = UUID.randomUUID();

    publish("member.joined", eventId, "Whatever", Map.of("serverId", server));

    awaitProcessed(eventId);
    await().atMost(Duration.ofSeconds(5)).until(() -> messages(QUEUE) == 0);
    assertThat(messages(QUEUE + ".dlq")).isZero();
    assertThat(count("server_members", "server_id", server)).isZero();
  }

  // Payload chứa serverId và channelId
  private static Map<String, Object> ids(UUID server, UUID channel) {
    return Map.of("serverId", server, "channelId", channel);
  }

  // Payload MemberJoined hợp lệ cho user vào server
  private static Map<String, Object> member(UUID server, UUID user) {
    return Map.of("serverId", server, "userId", user, "joinedAt", Instant.now().toString());
  }

  // Chạy các lần publish trên cùng một channel AMQP để broker nhận đúng thứ tự
  private void inOrder(Runnable publishes) {
    rabbit.invoke(
        operations -> {
          publishes.run();
          return null;
        });
  }

  // Gửi envelope thẳng vào exchange community.events với routing key cho trước
  private void publish(String routingKey, UUID eventId, String type, Map<String, Object> payload) {
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            eventId,
            "eventType",
            type,
            "eventVersion",
            1,
            "occurredAt",
            Instant.now().toString(),
            "producer",
            "community-service",
            "aggregateType",
            "Test",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        "community.events",
        routingKey,
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Số message sẵn sàng trong queue
  private long messages(String queue) {
    return rabbit.execute(channel -> channel.messageCount(queue));
  }

  // Số dòng của bảng notification.table có cột column bằng value
  private int count(String table, String column, UUID value) {
    return jdbc.queryForObject(
        "select count(*) from notification." + table + " where " + column + " = ?",
        Integer.class,
        value);
  }

  // Số dòng inbox của consumer này cho eventId
  private int processedCount(UUID eventId) {
    return jdbc.queryForObject(
        "select count(*) from notification.processed_event where event_id = ? and consumer = ?",
        Integer.class,
        eventId,
        QUEUE);
  }

  // Chờ tới khi event đã được commit vào inbox
  private void awaitProcessed(UUID eventId) {
    await().atMost(Duration.ofSeconds(10)).until(() -> processedCount(eventId) == 1);
  }
}

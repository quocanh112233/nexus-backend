package com.nexus.notification.interfaces.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.notification.domain.readstate.ChannelReadState;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.UserId;
import com.nexus.testsupport.RabbitMqContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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

// Kiểm tra listener messaging.events với RabbitMQ và Postgres thật: unread, mention, outbox, DLQ
@SpringBootTest(properties = "management.server.port=0")
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class MessagingEventListenerIT extends NotificationIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String QUEUE = "notification.messaging.events";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private ReadStateRepository readStates;

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

  // Kịch bản: hai tin cùng kênh đến đảo thứ tự
  // Input: MessageCreated id lớn rồi MessageCreated id nhỏ cùng channelId
  // Output: channel_activity.last_message_id bằng id lớn
  @Test
  void outOfOrderMessagesKeepGreatestLastMessageId() {
    UUID channel = UUID.randomUUID();
    UUID big = big();
    UUID small = small();
    UUID last = UUID.randomUUID();
    inOrder(
        () -> {
          message(UUID.randomUUID(), big, channel, UUID.randomUUID(), UUID.randomUUID());
          message(last, small, channel, UUID.randomUUID(), UUID.randomUUID());
        });

    awaitProcessed(last);

    assertThat(lastMessageId(channel)).isEqualTo(big);
  }

  // Kịch bản: MessageCreated tới trước ChannelCreated của cùng kênh
  // Input: MessageCreated có serverId, xử lý xong mới publish ChannelCreated lên community.events
  // Output: đúng một dòng channel_activity, server_id đúng, last_message_id bằng messageId
  @Test
  void messageBeforeChannelCreatedStillTracksServer() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID sent = UUID.randomUUID();
    UUID created = UUID.randomUUID();
    message(sent, messageId, channel, server, UUID.randomUUID());
    awaitProcessed(sent);

    send(
        "community.events",
        "channel.created",
        created,
        "ChannelCreated",
        Map.of("serverId", server, "channelId", channel));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> processedCount(created, "notification.community.events") == 1);
    assertThat(count("channel_activity", "channel_id", channel)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select server_id from notification.channel_activity where channel_id = ?",
                UUID.class,
                channel))
        .isEqualTo(server);
    assertThat(lastMessageId(channel)).isEqualTo(messageId);
  }

  // Kịch bản: mention người không thuộc server
  // Input: MessageCreated kênh server mention user không có trong server_members
  // Output: không dòng mentions, không dòng read_states của user, outbox không có MentionCreated
  @Test
  void mentionOfNonMemberDoesNotCount() {
    UUID outsider = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    message(
        eventId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        outsider);

    awaitProcessed(eventId);
    assertThat(count("mentions", "user_id", outsider)).isZero();
    assertThat(count("read_states", "user_id", outsider)).isZero();
    assertThat(mentionOutbox(outsider)).isZero();
  }

  // Kịch bản: cùng tin mention member được giao lại và phát lại với eventId khác
  // Input: cùng eventId giao hai lần, rồi eventId khác cùng messageId, target là member
  // Output: mention_count = 1, đúng 1 MentionCreated trong outbox với routing key mention.created
  @Test
  void mentionOfMemberCountsOnceEvenIfRedelivered() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID author = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    member(server, target);
    UUID eventId = UUID.randomUUID();
    UUID replay = UUID.randomUUID();
    inOrder(
        () -> {
          message(eventId, messageId, channel, server, author, target);
          message(eventId, messageId, channel, server, author, target);
          message(replay, messageId, channel, server, author, target);
        });

    awaitProcessed(replay);

    assertThat(mentionCount(target, channel)).isEqualTo(1);
    assertThat(mentionOutbox(target)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select routing_key from notification.outbox_event"
                    + " where event_type = 'MentionCreated' and payload->>'userId' = ?",
                String.class,
                target.toString()))
        .isEqualTo("mention.created");
  }

  // Kịch bản: user đã đọc qua tin được mention
  // Input: read_states của target có last_read lớn hơn messageId, rồi MessageCreated mention target
  // Output: mention_count vẫn 0, dòng mentions vẫn có
  @Test
  void mentionAlreadyBelowReadPointNotCounted() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    member(server, target);
    jdbc.update(
        "insert into notification.read_states"
            + " (user_id, channel_id, last_read_message_id, mention_count, updated_at)"
            + " values (?, ?, ?, 0, now())",
        target,
        channel,
        big());
    UUID eventId = UUID.randomUUID();

    message(eventId, small(), channel, server, UUID.randomUUID(), target);

    awaitProcessed(eventId);
    assertThat(mentionCount(target, channel)).isZero();
    assertThat(count("mentions", "user_id", target)).isEqualTo(1);
  }

  // Kịch bản: tác giả là member gửi tin, một member khác chưa đọc (FR-090)
  // Input: author và other cùng server, MessageCreated của author
  // Output: last_read của author = messageId, listFor(author) unread=false, listFor(other) true
  @Test
  void ownMessageIsNotUnread() {
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    UUID author = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    member(server, author);
    member(server, other);
    UUID messageId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    message(eventId, messageId, channel, server, author);

    awaitProcessed(eventId);
    assertThat(
            jdbc.queryForObject(
                "select last_read_message_id from notification.read_states"
                    + " where user_id = ? and channel_id = ?",
                UUID.class,
                author,
                channel))
        .isEqualTo(messageId);
    assertThat(state(author, channel).unread()).isFalse();
    assertThat(state(other, channel).unread()).isTrue();
  }

  // Kịch bản: tin DM mention người kia (FR-093)
  // Input: DmChannelCreated (a, b) rồi MessageCreated serverId null của a mention b
  // Output: mention_count của b = 1, listFor(b) unread=true, MentionCreated.serverId là JSON null
  @Test
  void dmMessageMentionAndUnread() {
    UUID channel = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    inOrder(
        () -> {
          send(
              "messaging.events",
              "dm-channel.created",
              UUID.randomUUID(),
              "DmChannelCreated",
              Map.of("channelId", channel, "participantIds", List.of(a, b)));
          message(eventId, UUID.randomUUID(), channel, null, a, b);
        });

    awaitProcessed(eventId);

    assertThat(mentionCount(b, channel)).isEqualTo(1);
    assertThat(state(b, channel).unread()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select jsonb_typeof(payload->'serverId') from notification.outbox_event"
                    + " where event_type = 'MentionCreated' and payload->>'userId' = ?",
                String.class,
                b.toString()))
        .isEqualTo("null");
  }

  // Kịch bản: MessageCreated thiếu messageId
  // Input: payload chỉ có channelId, authorId, createdAt
  // Output: trong 5 giây DLQ có 1 message, queue chính rỗng, không có dòng processed_event
  @Test
  void poisonMessageCreatedGoesToDlq() {
    UUID eventId = UUID.randomUUID();

    send(
        "messaging.events",
        "message.created",
        eventId,
        "MessageCreated",
        Map.of(
            "channelId",
            UUID.randomUUID(),
            "authorId",
            UUID.randomUUID(),
            "createdAt",
            Instant.now().toString()));

    await().atMost(Duration.ofSeconds(5)).until(() -> messages(QUEUE + ".dlq") == 1);
    assertThat(messages(QUEUE)).isZero();
    assertThat(processedCount(eventId, QUEUE)).isZero();
  }

  // UUID ngẫu nhiên có byte đầu 0xff, lớn hơn mọi id của small()
  private static UUID big() {
    return UUID.fromString("ff" + UUID.randomUUID().toString().substring(2));
  }

  // UUID ngẫu nhiên có byte đầu 0x00, nhỏ hơn mọi id của big()
  private static UUID small() {
    return UUID.fromString("00" + UUID.randomUUID().toString().substring(2));
  }

  // Seed thành viên server trực tiếp bằng SQL
  private void member(UUID server, UUID user) {
    jdbc.update(
        "insert into notification.server_members (server_id, user_id, joined_at)"
            + " values (?, ?, now())",
        server,
        user);
  }

  // Publish MessageCreated lên messaging.events, serverId null nghĩa là tin DM
  private void message(
      UUID eventId, UUID messageId, UUID channel, UUID server, UUID author, UUID... mentions) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("messageId", messageId);
    payload.put("channelId", channel);
    payload.put("serverId", server);
    payload.put("authorId", author);
    payload.put("content", "hi");
    payload.put("mentionUserIds", List.of(mentions));
    payload.put("createdAt", Instant.now().toString());
    send("messaging.events", "message.created", eventId, "MessageCreated", payload);
  }

  // Chạy các lần publish trên cùng một channel AMQP để broker nhận đúng thứ tự
  private void inOrder(Runnable publishes) {
    rabbit.invoke(
        operations -> {
          publishes.run();
          return null;
        });
  }

  // Gửi envelope thẳng vào exchange với routing key cho trước
  private void send(
      String exchange, String routingKey, UUID eventId, String type, Map<String, Object> payload) {
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
            "messaging-service",
            "aggregateType",
            "Test",
            "aggregateId",
            UUID.randomUUID(),
            "payload",
            payload);
    rabbit.send(
        exchange,
        routingKey,
        MessageBuilder.withBody(MAPPER.writeValueAsBytes(envelope))
            .setContentType("application/json")
            .setMessageId(eventId.toString())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build());
  }

  // Read state của user ở kênh theo listFor
  private ChannelReadState state(UUID user, UUID channel) {
    return readStates.listFor(new UserId(user)).stream()
        .filter(s -> s.channelId().value().equals(channel))
        .findFirst()
        .orElseThrow();
  }

  // Tin mới nhất của kênh trong channel_activity
  private UUID lastMessageId(UUID channel) {
    return jdbc.queryForObject(
        "select last_message_id from notification.channel_activity where channel_id = ?",
        UUID.class,
        channel);
  }

  // mention_count của user ở kênh
  private int mentionCount(UUID user, UUID channel) {
    return jdbc.queryForObject(
        "select mention_count from notification.read_states where user_id = ? and channel_id = ?",
        Integer.class,
        user,
        channel);
  }

  // Số dòng outbox MentionCreated gửi cho user
  private int mentionOutbox(UUID user) {
    return jdbc.queryForObject(
        "select count(*) from notification.outbox_event"
            + " where event_type = 'MentionCreated' and payload->>'userId' = ?",
        Integer.class,
        user.toString());
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

  // Số dòng inbox của consumer cho eventId
  private int processedCount(UUID eventId, String consumer) {
    return jdbc.queryForObject(
        "select count(*) from notification.processed_event where event_id = ? and consumer = ?",
        Integer.class,
        eventId,
        consumer);
  }

  // Chờ tới khi event của queue messaging đã được commit vào inbox
  private void awaitProcessed(UUID eventId) {
    await().atMost(Duration.ofSeconds(10)).until(() -> processedCount(eventId, QUEUE) == 1);
  }
}

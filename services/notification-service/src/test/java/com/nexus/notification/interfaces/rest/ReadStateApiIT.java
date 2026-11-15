package com.nexus.notification.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.notification.NotificationIntegrationTest;
import com.nexus.notification.application.community.ApplyCommunityEventUseCase;
import com.nexus.notification.application.community.CommunityEvent;
import com.nexus.notification.domain.readstate.MentionRepository;
import com.nexus.notification.domain.readstate.ReadStateRepository;
import com.nexus.notification.domain.shared.ChannelId;
import com.nexus.notification.domain.shared.MessageId;
import com.nexus.notification.domain.shared.ServerId;
import com.nexus.notification.domain.shared.UserId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra REST read-states với Postgres thật: liệt kê, đánh dấu đã đọc, outbox, 404, đua mention
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ReadStateApiIT extends NotificationIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;
  @Autowired private ReadStateRepository readStates;
  @Autowired private MentionRepository mentions;
  @Autowired private ApplyCommunityEventUseCase community;
  @LocalServerPort private int port;

  // Kịch bản: gọi không kèm token
  // Input: GET và PUT /read-states không có Authorization
  // Output: cả hai trả 401 UNAUTHENTICATED
  @Test
  void unauthenticatedGets401() throws Exception {
    HttpResponse<String> get = send("GET", "/read-states", null, null);
    HttpResponse<String> put =
        send("PUT", "/read-states/" + UUID.randomUUID(), null, body(UUID.randomUUID()));

    assertThat(get.statusCode()).isEqualTo(401);
    assertThat(put.statusCode()).isEqualTo(401);
    assertThat(MAPPER.readTree(get.body()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  // Kịch bản: user là member S1 (2 kênh) và participant 1 DM, không phải member S2
  // Input: GET /read-states; kênh 1 chưa đọc kèm 2 mention, kênh 2 đã đọc hết, DM chưa đọc
  // Output: chỉ 3 kênh của user theo thứ tự channelId, serverId null với DM, không có kênh S2
  @Test
  void getListsOnlyMyChannelsWithUnreadAndMentions() throws Exception {
    UUID user = UUID.randomUUID();
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    UUID unreadChannel = UUID.randomUUID();
    UUID readChannel = UUID.randomUUID();
    UUID dm = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    member(s1, user);
    activity(unreadChannel, s1, id(0x90));
    activity(readChannel, s1, id(0x50));
    activity(dm, null, id(0x60));
    activity(foreign, s2, id(0x70));
    jdbc.update(
        "insert into notification.dm_participants (channel_id, user_id) values (?, ?)", dm, user);
    readState(user, unreadChannel, id(0x30), 2);
    readState(user, readChannel, id(0x50), 0);

    JsonNode items = MAPPER.readTree(send("GET", "/read-states", user, null).body()).get("items");

    List<String> expected =
        new ArrayList<>(List.of(unreadChannel.toString(), readChannel.toString(), dm.toString()));
    expected.sort(null);
    assertThat(texts(items, "channelId")).containsExactlyElementsOf(expected);
    JsonNode unread = byChannel(items, unreadChannel);
    assertThat(unread.get("serverId").asText()).isEqualTo(s1.toString());
    assertThat(unread.get("unread").asBoolean()).isTrue();
    assertThat(unread.get("mentionCount").asInt()).isEqualTo(2);
    assertThat(unread.get("lastReadMessageId").asText()).isEqualTo(id(0x30).toString());
    assertThat(unread.get("lastMessageId").asText()).isEqualTo(id(0x90).toString());
    assertThat(byChannel(items, readChannel).get("unread").asBoolean()).isFalse();
    JsonNode dmItem = byChannel(items, dm);
    assertThat(dmItem.get("serverId").isNull()).isTrue();
    assertThat(dmItem.get("lastReadMessageId").isNull()).isTrue();
    assertThat(dmItem.get("unread").asBoolean()).isTrue();
  }

  // Kịch bản: PUT id lớn rồi id nhỏ hơn (mốc đọc chỉ tiến)
  // Input: PUT lớn, PUT nhỏ, GET
  // Output: cả hai PUT trả lastReadMessageId = id lớn; GET xác nhận id lớn
  @Test
  void putOlderIdDoesNotMoveBack() throws Exception {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    member(server, user);
    activity(channel, server, id(0xf0));
    UUID big = id(0xa0);
    UUID small = id(0x20);

    JsonNode first = json(send("PUT", "/read-states/" + channel, user, body(big)));
    JsonNode second = json(send("PUT", "/read-states/" + channel, user, body(small)));

    assertThat(first.get("lastReadMessageId").asText()).isEqualTo(big.toString());
    assertThat(second.get("lastReadMessageId").asText()).isEqualTo(big.toString());
    assertThat(second.get("channelId").asText()).isEqualTo(channel.toString());
    JsonNode items = json(send("GET", "/read-states", user, null)).get("items");
    assertThat(byChannel(items, channel).get("lastReadMessageId").asText())
        .isEqualTo(big.toString());
  }

  // Kịch bản: 3 mention a<b<c, đọc tới b rồi tới c
  // Input: PUT b, PUT c
  // Output: mentionCount = 1 rồi 0
  @Test
  void putReducesMentionsOnlyUpToReadPoint() throws Exception {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    member(server, user);
    activity(channel, server, id(0xf0));
    for (int prefix : new int[] {0x10, 0x20, 0x30}) {
      mention(id(prefix), user, channel, server);
    }

    JsonNode atB = json(send("PUT", "/read-states/" + channel, user, body(id(0x20))));
    JsonNode atC = json(send("PUT", "/read-states/" + channel, user, body(id(0x30))));

    assertThat(atB.get("mentionCount").asInt()).isEqualTo(1);
    assertThat(atC.get("mentionCount").asInt()).isZero();
  }

  // Kịch bản: PUT hợp lệ ghi event cùng transaction
  // Input: 1 mention sau mốc, PUT mốc đứng trước nó
  // Output: đúng 1 dòng outbox ReadStateUpdated, payload.userId = caller, mentionCount khớp
  @Test
  void putWritesReadStateUpdatedToOutbox() throws Exception {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    member(server, user);
    activity(channel, server, id(0xf0));
    mention(id(0x80), user, channel, server);

    send("PUT", "/read-states/" + channel, user, body(id(0x40)));

    List<String> payloads =
        jdbc.queryForList(
            "select payload::text from notification.outbox_event"
                + " where event_type = 'ReadStateUpdated' and aggregate_id = ?",
            String.class,
            user);
    assertThat(payloads).hasSize(1);
    JsonNode payload = MAPPER.readTree(payloads.get(0));
    assertThat(payload.get("userId").asText()).isEqualTo(user.toString());
    assertThat(payload.get("channelId").asText()).isEqualTo(channel.toString());
    assertThat(payload.get("lastReadMessageId").asText()).isEqualTo(id(0x40).toString());
    assertThat(payload.get("mentionCount").asInt()).isEqualTo(1);
  }

  // Kịch bản: kênh lạ, kênh của server user không tham gia, kênh đã xóa
  // Input: PUT cho ba kênh
  // Output: cùng status 404, cùng code NOT_FOUND và cùng detail
  @Test
  void putUnknownOrForeignOrDeletedChannelIs404WithSameBody() throws Exception {
    UUID user = UUID.randomUUID();
    UUID mine = UUID.randomUUID();
    UUID foreignServer = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    UUID deleted = UUID.randomUUID();
    member(mine, user);
    activity(foreign, foreignServer, id(0x70));
    activity(deleted, mine, id(0x70));
    jdbc.update(
        "update notification.channel_activity set deleted_at = now() where channel_id = ?",
        deleted);

    List<HttpResponse<String>> responses = new ArrayList<>();
    for (UUID channel : List.of(UUID.randomUUID(), foreign, deleted)) {
      responses.add(send("PUT", "/read-states/" + channel, user, body(id(0x10))));
    }

    for (HttpResponse<String> response : responses) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(json(response).get("code").asText()).isEqualTo("NOT_FOUND");
      assertThat(json(response).get("detail").asText())
          .isEqualTo(json(responses.get(0)).get("detail").asText());
    }
  }

  // Kịch bản: UUID sai ở path, ở body, thiếu giá trị và thiếu body
  // Input: PUT path abc; body "abc"; body {}; không body
  // Output: cả bốn trả 400 VALIDATION_FAILED
  @Test
  void putInvalidUuidIs400() throws Exception {
    UUID user = UUID.randomUUID();
    String valid = "/read-states/" + UUID.randomUUID();

    List<HttpResponse<String>> responses =
        List.of(
            send("PUT", "/read-states/abc", user, body(UUID.randomUUID())),
            send("PUT", valid, user, "{\"lastReadMessageId\":\"abc\"}"),
            send("PUT", valid, user, "{}"),
            send("PUT", valid, user, null));

    for (HttpResponse<String> response : responses) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(json(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: member bị kick qua event community
  // Input: user có read state, ApplyCommunityEventUseCase xử lý MemberKicked, rồi GET và PUT
  // Output: GET không còn kênh; PUT trả 404
  @Test
  void kickedUserNoLongerSeesChannel() throws Exception {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    member(server, user);
    activity(channel, server, id(0x90));
    readState(user, channel, id(0x10), 1);
    assertThat(json(send("GET", "/read-states", user, null)).get("items")).hasSize(1);

    community.execute(
        new CommunityEvent(UUID.randomUUID(), "MemberKicked", server, null, user, null));

    assertThat(json(send("GET", "/read-states", user, null)).get("items")).isEmpty();
    assertThat(send("PUT", "/read-states/" + channel, user, body(id(0x20))).statusCode())
        .isEqualTo(404);
  }

  // Kịch bản: 20 PUT (id 0x10..0x23) chạy song song với 20 mention mới (id 0x18..0x2b)
  // Input: 40 tác vụ xuất phát cùng lúc qua latch, số vòng cố định
  // Output: mốc đọc cuối = 0x23, mention_count = 8 = số mention có id lớn hơn mốc đọc (SQL)
  @Test
  void concurrentPutAndMentionKeepsCountConsistent() throws Exception {
    UUID user = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    UUID channel = UUID.randomUUID();
    member(server, user);
    activity(channel, server, id(0xf0));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 20; i++) {
        UUID readId = id(0x10 + i);
        UUID mentionId = id(0x18 + i);
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return send("PUT", "/read-states/" + channel, user, body(readId)).statusCode();
                }));
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  tx.executeWithoutResult(
                      status -> {
                        mentions.insertIfAbsent(
                            new MessageId(mentionId),
                            new UserId(user),
                            new ChannelId(channel),
                            new ServerId(server),
                            new UserId(UUID.randomUUID()),
                            Instant.now());
                        readStates.addMentionCount(
                            new UserId(user), new ChannelId(channel), new MessageId(mentionId));
                      });
                  return 200;
                }));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(200);
      }
    } finally {
      pool.shutdownNow();
    }

    Integer stored =
        jdbc.queryForObject(
            "select mention_count from notification.read_states"
                + " where user_id = ? and channel_id = ?",
            Integer.class,
            user,
            channel);
    Integer actual =
        jdbc.queryForObject(
            "select count(*) from notification.mentions m"
                + " join notification.read_states r"
                + " on r.user_id = m.user_id and r.channel_id = m.channel_id"
                + " where m.user_id = ? and m.channel_id = ?"
                + " and m.message_id > r.last_read_message_id",
            Integer.class,
            user,
            channel);
    assertThat(stored).isEqualTo(actual).isEqualTo(8);
    assertThat(
            jdbc.queryForObject(
                "select last_read_message_id from notification.read_states"
                    + " where user_id = ? and channel_id = ?",
                UUID.class,
                user,
                channel))
        .isEqualTo(id(0x23));
  }

  // UUID cố định có byte đầu bằng prefix nên thứ tự byte theo prefix
  private static UUID id(int prefix) {
    return new UUID(((long) prefix << 56) | 0x7000L, 0x8000_0000_0000_0001L);
  }

  // Seed thành viên server bằng SQL, join sớm hơn mọi tin được seed
  private void member(UUID server, UUID user) {
    jdbc.update(
        "insert into notification.server_members (server_id, user_id, joined_at)"
            + " values (?, ?, now() - interval '1 hour')",
        server,
        user);
  }

  // Seed kênh có tin mới nhất bằng SQL, serverId null nghĩa là DM
  private void activity(UUID channel, UUID server, UUID lastMessage) {
    jdbc.update(
        "insert into notification.channel_activity"
            + " (channel_id, server_id, last_message_id, last_message_at) values (?, ?, ?, now())",
        channel,
        server,
        lastMessage);
  }

  // Seed read state bằng SQL
  private void readState(UUID user, UUID channel, UUID lastRead, int mentionCount) {
    jdbc.update(
        "insert into notification.read_states"
            + " (user_id, channel_id, last_read_message_id, mention_count, updated_at)"
            + " values (?, ?, ?, ?, now())",
        user,
        channel,
        lastRead,
        mentionCount);
  }

  // Seed mention bằng SQL
  private void mention(UUID message, UUID user, UUID channel, UUID server) {
    jdbc.update(
        "insert into notification.mentions"
            + " (message_id, user_id, channel_id, server_id, author_id, created_at)"
            + " values (?, ?, ?, ?, ?, now())",
        message,
        user,
        channel,
        server,
        UUID.randomUUID());
  }

  // Body PUT chứa lastReadMessageId
  private static String body(UUID lastRead) {
    return "{\"lastReadMessageId\":\"" + lastRead + "\"}";
  }

  // Phân tích body phản hồi thành JSON
  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return MAPPER.readTree(response.body());
  }

  // Lấy giá trị chuỗi của một trường trong từng phần tử
  private static List<String> texts(JsonNode items, String field) {
    List<String> out = new ArrayList<>();
    items.forEach(n -> out.add(n.get(field).asText()));
    return out;
  }

  // Tìm phần tử theo channelId
  private static JsonNode byChannel(JsonNode items, UUID channel) {
    for (JsonNode n : items) {
      if (n.get("channelId").asText().equals(channel.toString())) {
        return n;
      }
    }
    throw new AssertionError("channel not in response " + channel);
  }

  // Gọi API với Bearer của user (null thì không gửi token), body null thì không gửi body
  private HttpResponse<String> send(String method, String path, UUID user, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
    if (user != null) {
      builder.header("Authorization", bearer(user));
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}

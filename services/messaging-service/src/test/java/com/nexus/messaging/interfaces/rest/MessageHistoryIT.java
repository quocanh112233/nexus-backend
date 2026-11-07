package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.message.MessageId;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra GET /channels/{id}/messages: keyset hai chiều, tombstone, reaction summary, IDOR, quyền
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class MessageHistoryIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory emf;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;

  private final UUID serverId = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID viewer = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Cho mọi user quyền xem kênh và đọc lịch sử trước mỗi test
  @BeforeEach
  void grantAll() {
    allow(ChannelPermission.VIEW_CHANNEL, ChannelPermission.READ_MESSAGE_HISTORY);
  }

  // Kịch bản: lấy trang mới nhất khi kênh có 5 tin
  // Input: GET không tham số cursor, limit=3
  // Output: 200, đúng 3 tin mới nhất theo id giảm dần
  @Test
  void latestPageIsDescendingAndStopsAtLimit() throws Exception {
    List<UUID> ids = seed(channel, 5);

    HttpResponse<String> res = get(channel, "limit=3", viewer);

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(idsOf(res)).containsExactly(ids.get(4), ids.get(3), ids.get(2));
  }

  // Kịch bản: đi hết 120 tin bằng before của tin cuối trang trước
  // Input: GET limit=50 lặp với before là id cuối của trang trước
  // Output: ghép lại đúng 120 id giảm dần, không trùng, không sót
  @Test
  void beforeCursorPagesWithoutOverlapOrGap() throws Exception {
    List<UUID> ids = seed(channel, 120);
    List<UUID> seen = new ArrayList<>();
    String query = "limit=50";

    while (true) {
      List<UUID> page = idsOf(get(channel, query, viewer));
      if (page.isEmpty()) {
        break;
      }
      seen.addAll(page);
      query = "limit=50&before=" + page.get(page.size() - 1);
    }

    List<UUID> expected = new ArrayList<>(ids);
    Collections.reverse(expected);
    assertThat(seen).containsExactlyElementsOf(expected);
    assertThat(new HashSet<>(seen)).hasSize(120);
  }

  // Kịch bản: lấy tin mới hơn một mốc
  // Input: after=id thứ 3 của 10 tin, limit=100 rồi limit=2
  // Output: id tăng dần chỉ gồm tin mới hơn mốc; limit=2 lấy hai tin liền sau mốc
  @Test
  void afterCursorReturnsAscendingNewerOnly() throws Exception {
    List<UUID> ids = seed(channel, 10);

    List<UUID> all = idsOf(get(channel, "limit=100&after=" + ids.get(3), viewer));
    List<UUID> two = idsOf(get(channel, "limit=2&after=" + ids.get(3), viewer));

    assertThat(all).containsExactlyElementsOf(ids.subList(4, 10));
    assertThat(two).containsExactly(ids.get(4), ids.get(5));
  }

  // Kịch bản: tin đã xóa mềm còn sót nội dung gốc, mention và attachment
  // Input: GET trang gồm tin đó
  // Output: deleted=true, content null, mảng attachments/mentions/reactions rỗng, còn id, authorId
  @Test
  void deletedMessageIsTombstone() throws Exception {
    UUID id = insert(channel, "bí mật", null, false);
    jdbc.update("update messaging.messages set deleted_at = now() where id = ?", id);
    UUID mentioned = UUID.randomUUID();
    jdbc.update(
        "insert into messaging.message_mentions (message_id, user_id) values (?, ?)",
        id,
        mentioned);
    attach(id);

    JsonNode m = MAPPER.readTree(get(channel, "", viewer).body()).get(0);

    assertThat(m.get("id").asText()).isEqualTo(id.toString());
    assertThat(m.get("authorId").asText()).isEqualTo(author.toString());
    assertThat(m.get("createdAt").asText()).isNotBlank();
    assertThat(m.get("deleted").asBoolean()).isTrue();
    assertThat(m.get("content").isNull()).isTrue();
    assertThat(m.get("attachments")).isEmpty();
    assertThat(m.get("mentionUserIds")).isEmpty();
    assertThat(m.get("reactions")).isEmpty();
  }

  // Kịch bản: tin trả lời một tin đã bị xóa
  // Input: GET trang gồm tin gốc đã xóa và tin reply
  // Output: tin reply vẫn có replyToId trỏ tới tin đã xóa
  @Test
  void replyToDeletedMessageStillListsReplyId() throws Exception {
    UUID gone = insert(channel, "", null, true);
    UUID reply = insert(channel, "trả lời", gone, false);

    JsonNode page = MAPPER.readTree(get(channel, "", viewer).body());

    assertThat(page.get(0).get("id").asText()).isEqualTo(reply.toString());
    assertThat(page.get(0).get("replyToId").asText()).isEqualTo(gone.toString());
    assertThat(page.get(1).get("deleted").asBoolean()).isTrue();
  }

  // Kịch bản: tin có reaction của nhiều user, người xem là một trong số đó
  // Input: 👍 từ 2 user (có viewer), ❤ từ 1 user khác, rồi người xem khác gọi
  // Output: 👍 count 2 me=true cho viewer và me=false cho người khác; ❤ count 1 me=false
  @Test
  void reactionSummaryCountsAndMarksMe() throws Exception {
    UUID id = insert(channel, "hi", null, false);
    react(id, viewer, "👍");
    react(id, UUID.randomUUID(), "👍");
    react(id, UUID.randomUUID(), "❤");

    JsonNode mine = MAPPER.readTree(get(channel, "", viewer).body()).get(0).get("reactions");
    JsonNode others =
        MAPPER.readTree(get(channel, "", UUID.randomUUID()).body()).get(0).get("reactions");

    assertThat(mine).hasSize(2);
    assertThat(reaction(mine, "👍").get("count").asInt()).isEqualTo(2);
    assertThat(reaction(mine, "👍").get("me").asBoolean()).isTrue();
    assertThat(reaction(mine, "❤").get("count").asInt()).isEqualTo(1);
    assertThat(reaction(mine, "❤").get("me").asBoolean()).isFalse();
    assertThat(reaction(others, "👍").get("me").asBoolean()).isFalse();
  }

  // Kịch bản: hai kênh cùng có tin
  // Input: GET lịch sử kênh A
  // Output: chỉ id của kênh A, không có id của kênh B
  @Test
  void otherChannelMessagesNotIncluded() throws Exception {
    List<UUID> mine = seed(channel, 3);
    List<UUID> theirs = seed(UUID.randomUUID(), 3);

    List<UUID> page = idsOf(get(channel, "", viewer));

    assertThat(page).containsExactlyInAnyOrderElementsOf(mine);
    assertThat(page).doesNotContainAnyElementsOf(theirs);
  }

  // Kịch bản: tham số sai
  // Input: before/after không phải UUID, cả hai cùng lúc, limit 0, 101 và chữ
  // Output: tất cả 400 VALIDATION_FAILED
  @Test
  void invalidCursorIs400() throws Exception {
    String id = MessageId.newId().value().toString();
    List<String> bad =
        List.of(
            "before=abc",
            "after=abc",
            "before=" + id + "&after=" + id,
            "limit=0",
            "limit=101",
            "limit=abc");

    for (String query : bad) {
      HttpResponse<String> res = get(channel, query, viewer);
      assertThat(res.statusCode()).as(query).isEqualTo(400);
      assertThat(MAPPER.readTree(res.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }
  }

  // Kịch bản: user không thấy kênh, và user thấy kênh nhưng thiếu READ_MESSAGE_HISTORY
  // Input: quyền rỗng rồi chỉ VIEW_CHANNEL
  // Output: 404 NOT_FOUND rồi 403 PERMISSION_DENIED
  @Test
  void hiddenChannelIs404() throws Exception {
    seed(channel, 1);
    allow();
    HttpResponse<String> hidden = get(channel, "", viewer);
    allow(ChannelPermission.VIEW_CHANNEL);
    HttpResponse<String> noHistory = get(channel, "", viewer);

    assertThat(hidden.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(hidden.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(noHistory.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(noHistory.body()).get("code").asText())
        .isEqualTo("PERMISSION_DENIED");
  }

  // Kịch bản: trang 50 tin, mỗi tin có mention, attachment và reaction
  // Input: một GET limit=50 với Hibernate statistics bật
  // Output: 3 đến 4 câu lệnh SQL (trang, mention, attachment, reaction), không tăng theo số tin
  @Test
  void pageLoadsRelationsWithConstantNumberOfQueries() throws Exception {
    for (UUID id : seed(channel, 50)) {
      jdbc.update(
          "insert into messaging.message_mentions (message_id, user_id) values (?, ?)",
          id,
          UUID.randomUUID());
      attach(id);
      react(id, viewer, "👍");
    }
    Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    JsonNode page = MAPPER.readTree(get(channel, "limit=50", viewer).body());

    assertThat(page).hasSize(50);
    assertThat(page.get(0).get("mentionUserIds")).hasSize(1);
    assertThat(page.get(0).get("attachments")).hasSize(1);
    assertThat(page.get(0).get("reactions")).hasSize(1);
    assertThat(stats.getPrepareStatementCount()).isBetween(3L, 4L);
  }

  // Cấp đúng các quyền cho mọi user trên mọi kênh
  private void allow(ChannelPermission... permissions) {
    EnumSet<ChannelPermission> set = EnumSet.noneOf(ChannelPermission.class);
    set.addAll(List.of(permissions));
    when(checker.check(any(), any())).thenReturn(new ChannelAccess(serverId, set));
  }

  // Chèn n tin cho kênh theo thứ tự tăng dần và trả id theo thứ tự đó
  private List<UUID> seed(UUID channelId, int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(insert(channelId, "tin " + i, null, false));
    }
    return ids;
  }

  // Chèn một tin trực tiếp bằng JDBC, có thể là tin đã xóa mềm
  private UUID insert(UUID channelId, String content, UUID replyTo, boolean deleted) {
    UUID id = MessageId.newId().value();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "insert into messaging.messages"
            + " (id, channel_id, author_id, content, reply_to_id, created_at, deleted_at)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        id,
        channelId,
        author,
        deleted ? "" : content,
        replyTo,
        now,
        deleted ? now : null);
    return id;
  }

  // Chèn một tệp đính kèm cho tin
  private void attach(UUID messageId) {
    jdbc.update(
        "insert into messaging.message_attachments"
            + " (id, message_id, public_id, url, content_type, bytes, original_filename)"
            + " values (?, ?, 'p', 'https://x/y.png', 'image/png', 1, 'y.png')",
        UUID.randomUUID(),
        messageId);
  }

  // Chèn một reaction của user lên tin
  private void react(UUID messageId, UUID user, String emoji) {
    jdbc.update(
        "insert into messaging.reactions (message_id, user_id, emoji, created_at)"
            + " values (?, ?, ?, now())",
        messageId,
        user,
        emoji);
  }

  // Tìm phần tử reaction theo emoji trong mảng JSON
  private static JsonNode reaction(JsonNode reactions, String emoji) {
    for (JsonNode r : reactions) {
      if (emoji.equals(r.get("emoji").asText())) {
        return r;
      }
    }
    throw new AssertionError("no reaction " + emoji);
  }

  // Danh sách id trong phản hồi theo đúng thứ tự trả về
  private static List<UUID> idsOf(HttpResponse<String> res) throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (JsonNode m : MAPPER.readTree(res.body())) {
      ids.add(UUID.fromString(m.get("id").asText()));
    }
    return ids;
  }

  // GET lịch sử kênh với query string và Bearer của user
  private HttpResponse<String> get(UUID channelId, String query, UUID user) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/channels/"
                        + channelId
                        + "/messages?"
                        + query))
            .header("Authorization", bearer(user))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.shared.ChannelPermission;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra PUT và DELETE /channels/{id}/messages/{id}/reactions/{emoji}: idempotent, IDOR, outbox
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ReactionIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String THUMBS_UP = "%F0%9F%91%8D";

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;

  private final UUID serverId = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Cho mọi user đủ quyền xem, gửi tin và thả reaction trước mỗi test
  @BeforeEach
  void grantAll() {
    allow(
        ChannelPermission.VIEW_CHANNEL,
        ChannelPermission.SEND_MESSAGES,
        ChannelPermission.ADD_REACTIONS);
  }

  // Kịch bản: 50 luồng cùng user thả cùng một emoji vào cùng tin
  // Input: 50 PUT đồng thời cùng URL
  // Output: tất cả 204, đúng một dòng reactions và đúng một outbox reaction.added
  @Test
  void fiftyThreadsReactingSameEmojiYieldOneRowAndOneOutboxEvent() throws Exception {
    String id = send(channel);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(50);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return put(channel, id, THUMBS_UP, author).statusCode();
                }));
      }
      start.countDown();
      for (Future<Integer> f : futures) {
        assertThat(f.get()).isEqualTo(204);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(rows(id)).isEqualTo(1);
    assertThat(outbox("reaction.added", id)).hasSize(1);
  }

  // Kịch bản: thả rồi gỡ reaction, mỗi thao tác gọi hai lần
  // Input: PUT x2 rồi DELETE x2 cùng emoji
  // Output: toàn 204, dòng DB về 0, mỗi loại một outbox với payload đúng catalog
  @Test
  void putThenDeleteRoundTrip() throws Exception {
    String id = send(channel);

    assertThat(put(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);
    assertThat(put(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);
    assertThat(rows(id)).isEqualTo(1);
    assertThat(delete(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);
    assertThat(delete(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);

    assertThat(rows(id)).isZero();
    assertThat(outbox("reaction.added", id)).hasSize(1);
    List<String> removed = outbox("reaction.removed", id);
    assertThat(removed).hasSize(1);
    JsonNode payload = MAPPER.readTree(removed.get(0));
    assertThat(payload.get("channelId").asText()).isEqualTo(channel.toString());
    assertThat(payload.get("userId").asText()).isEqualTo(author.toString());
    assertThat(payload.get("emoji").asText()).isEqualTo("👍");
  }

  // Kịch bản: emoji không hợp lệ trên URL
  // Input: PUT với "a" và "1"
  // Output: 400 VALIDATION_FAILED, không dòng DB, không outbox
  @Test
  void invalidEmojiIs400() throws Exception {
    String id = send(channel);

    HttpResponse<String> a = put(channel, id, "a", author);
    HttpResponse<String> one = put(channel, id, "1", author);

    assertThat(a.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(a.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(one.statusCode()).isEqualTo(400);
    assertThat(rows(id)).isZero();
    assertThat(outbox("reaction.added", id)).isEmpty();
  }

  // Kịch bản: emoji URL-encode UTF-8
  // Input: PUT với %F0%9F%91%8D
  // Output: 204, DB lưu đúng "👍"
  @Test
  void urlEncodedEmojiWorks() throws Exception {
    String id = send(channel);

    assertThat(put(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);

    assertThat(
            jdbc.queryForObject(
                "select emoji from messaging.reactions where message_id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("👍");
  }

  // Kịch bản: IDOR, tin kênh A thả reaction qua URL kênh B với đủ quyền ở B
  // Input: PUT và DELETE tin của A qua kênh B
  // Output: cả hai 404 NOT_FOUND, không dòng DB; tin đã xóa mềm cũng 404
  @Test
  void reactionOnOtherChannelsMessageIs404() throws Exception {
    String id = send(channel);
    UUID other = UUID.randomUUID();

    HttpResponse<String> put = put(other, id, THUMBS_UP, author);
    HttpResponse<String> del = delete(other, id, THUMBS_UP, author);

    assertThat(put.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(put.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(del.statusCode()).isEqualTo(404);
    assertThat(rows(id)).isZero();
    delete(channel, id);
    assertThat(put(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(404);
    assertThat(rows(id)).isZero();
  }

  // Kịch bản: user B gỡ reaction khi chỉ user A đã thả
  // Input: A PUT rồi B DELETE cùng emoji
  // Output: 204 nhưng dòng của A còn, không có outbox reaction.removed
  @Test
  void userCanRemoveOnlyOwnReaction() throws Exception {
    String id = send(channel);
    put(channel, id, THUMBS_UP, author);

    int status = delete(channel, id, THUMBS_UP, UUID.randomUUID()).statusCode();

    assertThat(status).isEqualTo(204);
    assertThat(rows(id)).isEqualTo(1);
    assertThat(outbox("reaction.removed", id)).isEmpty();
  }

  // Kịch bản: user chỉ có VIEW_CHANNEL
  // Input: PUT rồi DELETE
  // Output: PUT 403 PERMISSION_DENIED, DELETE 204
  @Test
  void putNeedsAddReactionsButDeleteOnlyView() throws Exception {
    String id = send(channel);
    allow(ChannelPermission.VIEW_CHANNEL);

    HttpResponse<String> put = put(channel, id, THUMBS_UP, author);

    assertThat(put.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(put.body()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(delete(channel, id, THUMBS_UP, author).statusCode()).isEqualTo(204);
  }

  // Cấp đúng các quyền cho mọi user trên mọi kênh
  private void allow(ChannelPermission... permissions) {
    EnumSet<ChannelPermission> set = EnumSet.noneOf(ChannelPermission.class);
    set.addAll(List.of(permissions));
    when(checker.check(any(), any())).thenReturn(new ChannelAccess(serverId, set));
  }

  // Số dòng reactions của tin
  private int rows(String messageId) {
    return jdbc.queryForObject(
        "select count(*) from messaging.reactions where message_id = ?",
        Integer.class,
        UUID.fromString(messageId));
  }

  // Payload các event theo routing key của một tin
  private List<String> outbox(String routingKey, String messageId) {
    return jdbc.queryForList(
        "select payload::text from messaging.outbox_event"
            + " where routing_key = ? and payload->>'messageId' = ?",
        String.class,
        routingKey,
        messageId);
  }

  // Gửi tin bằng tác giả mặc định và trả id
  private String send(UUID channelId) throws Exception {
    return MAPPER
        .readTree(
            HTTP.send(
                    HttpRequest.newBuilder(
                            URI.create(
                                "http://localhost:"
                                    + port
                                    + "/api/v1/channels/"
                                    + channelId
                                    + "/messages"))
                        .header("Authorization", bearer(author))
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                MAPPER.writeValueAsString(Map.of("content", "hi"))))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body())
        .get("id")
        .asText();
  }

  // URL reaction đã mã hóa sẵn của tin
  private URI url(UUID channelId, String id, String emoji) {
    return URI.create(
        "http://localhost:"
            + port
            + "/api/v1/channels/"
            + channelId
            + "/messages/"
            + id
            + "/reactions/"
            + emoji);
  }

  // PUT reaction với Bearer của user
  private HttpResponse<String> put(UUID channelId, String id, String emoji, UUID user)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId, id, emoji))
            .header("Authorization", bearer(user))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // DELETE reaction với Bearer của user
  private HttpResponse<String> delete(UUID channelId, String id, String emoji, UUID user)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId, id, emoji))
            .header("Authorization", bearer(user))
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // Xóa mềm tin bằng tác giả
  private void delete(UUID channelId, String id) throws Exception {
    HTTP.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/channels/"
                        + channelId
                        + "/messages/"
                        + id))
            .header("Authorization", bearer(author))
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

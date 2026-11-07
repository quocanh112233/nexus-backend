package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermission;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.application.shared.DependencyUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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

// Kiểm tra POST /channels/{id}/messages: idempotent theo nonce, phân quyền, validate và outbox
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class SendMessageIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;

  private final UUID serverId = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Cho mọi user đủ quyền xem và gửi tin trước mỗi test
  @BeforeEach
  void grantSend() {
    allow(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES);
  }

  // Kịch bản: gửi tin hợp lệ có mention
  // Input: POST {content, nonce} kèm một mention
  // Output: 201, Location, thân đủ trường, 1 dòng outbox message.created với payload đủ trường
  @Test
  void sendReturns201WithLocationAndOutboxRow() throws Exception {
    UUID mentioned = UUID.randomUUID();

    HttpResponse<String> response =
        post(channel, author, Map.of("content", "hi <@" + mentioned + ">", "nonce", "n-1"));

    JsonNode body = MAPPER.readTree(response.body());
    String id = body.get("id").asText();
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Location"))
        .contains("/api/v1/channels/" + channel + "/messages/" + id);
    assertThat(body.get("channelId").asText()).isEqualTo(channel.toString());
    assertThat(body.get("authorId").asText()).isEqualTo(author.toString());
    assertThat(body.get("content").asText()).isEqualTo("hi <@" + mentioned + ">");
    assertThat(body.get("mentionUserIds"))
        .extracting(JsonNode::asText)
        .containsExactly(mentioned.toString());
    assertThat(body.get("attachments")).isEmpty();
    assertThat(body.get("reactions")).isEmpty();
    assertThat(body.get("editedAt").isNull()).isTrue();
    assertThat(body.get("deleted").asBoolean()).isFalse();
    assertThat(body.get("version").asLong()).isZero();
    List<String> events = outbox(id);
    assertThat(events).hasSize(1);
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertThat(payload.get("messageId").asText()).isEqualTo(id);
    assertThat(payload.get("channelId").asText()).isEqualTo(channel.toString());
    assertThat(payload.get("serverId").asText()).isEqualTo(serverId.toString());
    assertThat(payload.get("authorId").asText()).isEqualTo(author.toString());
    assertThat(payload.get("content").asText()).isEqualTo("hi <@" + mentioned + ">");
    assertThat(payload.get("mentionUserIds"))
        .extracting(JsonNode::asText)
        .containsExactly(mentioned.toString());
    assertThat(payload.get("attachments")).isEmpty();
    assertThat(payload.get("nonce").asText()).isEqualTo("n-1");
    assertThat(payload.get("createdAt").asText()).isEqualTo(body.get("createdAt").asText());
    assertThat(
            jdbc.queryForObject(
                "select server_id from messaging.channel_refs where channel_id = ?",
                UUID.class,
                channel))
        .isEqualTo(serverId);
  }

  // Kịch bản: client retry cùng nonce
  // Input: hai POST cùng tác giả, cùng nonce
  // Output: 201 rồi 200 cùng id, đúng một dòng messages và một event
  @Test
  void sameNonceTwiceReturns200AndOneRow() throws Exception {
    Map<String, Object> body = Map.of("content", "hi", "nonce", "n-dup");

    HttpResponse<String> first = post(channel, author, body);
    HttpResponse<String> second = post(channel, author, body);

    String id = MAPPER.readTree(first.body()).get("id").asText();
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(second.body()).get("id").asText()).isEqualTo(id);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.messages where author_id = ? and nonce = 'n-dup'",
                Integer.class,
                author))
        .isEqualTo(1);
    assertThat(outbox(id)).hasSize(1);
  }

  // Kịch bản: 50 request đồng thời cùng tác giả, cùng nonce
  // Input: 50 POST song song nonce "n-race"
  // Output: đúng một 201, 49 cái còn lại 200, không 5xx, một dòng messages
  @Test
  void fiftyParallelSendsWithSameNonceCreateOneMessage() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(50);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        calls.add(
            () -> post(channel, author, Map.of("content", "hi", "nonce", "n-race")).statusCode());
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(calls)) {
        statuses.add(f.get());
      }

      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
      assertThat(statuses).filteredOn(s -> s == 200).hasSize(49);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from messaging.messages where author_id = ?",
                  Integer.class,
                  author))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  // Kịch bản: content hoặc nonce chứa ký tự NUL, hoặc content quá 8000 char
  // Input: content "a\u0000b", nonce "n\u0000x", content 8001 chữ a
  // Output: cả ba 400 VALIDATION_FAILED, không lưu tin
  @Test
  void nulOrOversizedBodyIs400AndStoresNothing() throws Exception {
    assertCode(post(channel, author, Map.of("content", "a\u0000b")), 400, "VALIDATION_FAILED");
    assertCode(
        post(channel, author, Map.of("content", "hi", "nonce", "n\u0000x")),
        400,
        "VALIDATION_FAILED");
    assertCode(
        post(channel, author, Map.of("content", "a".repeat(8001))), 400, "VALIDATION_FAILED");
    assertThat(countMessages()).isZero();
  }

  // Kịch bản: cùng tác giả dùng lại nonce ở kênh khác
  // Input: POST nonce "n-x" vào kênh A rồi vào kênh B
  // Output: lần 1 là 201, lần 2 là 400 VALIDATION_FAILED, kênh B không có tin
  @Test
  void sameNonceInOtherChannelIs400() throws Exception {
    UUID other = UUID.randomUUID();
    Map<String, Object> body = Map.of("content", "hi", "nonce", "n-x");

    HttpResponse<String> first = post(channel, author, body);
    HttpResponse<String> second = post(other, author, body);

    assertThat(first.statusCode()).isEqualTo(201);
    assertCode(second, 400, "VALIDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.messages where channel_id = ?",
                Integer.class,
                other))
        .isZero();
  }

  // Kịch bản: reply tới tin ở kênh khác
  // Input: tin ở kênh A, rồi POST vào kênh B với replyToId của tin đó
  // Output: 400 VALIDATION_FAILED, không có tin mới ở kênh B
  @Test
  void replyToMessageInOtherChannelIs400() throws Exception {
    String original =
        MAPPER.readTree(post(channel, author, Map.of("content", "a")).body()).get("id").asText();
    UUID other = UUID.randomUUID();

    HttpResponse<String> response =
        post(other, author, Map.of("content", "b", "replyToId", original));

    assertCode(response, 400, "VALIDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.messages where channel_id = ?",
                Integer.class,
                other))
        .isZero();
  }

  // Kịch bản: user thấy kênh nhưng thiếu SEND_MESSAGES
  // Input: quyền chỉ có VIEW_CHANNEL
  // Output: 403 PERMISSION_DENIED, không lưu tin
  @Test
  void forbiddenWhenMissingSendMessages403() throws Exception {
    allow(ChannelPermission.VIEW_CHANNEL);

    assertCode(post(channel, author, Map.of("content", "hi")), 403, "PERMISSION_DENIED");
    assertThat(countMessages()).isZero();
  }

  // Kịch bản: user không thấy kênh
  // Input: tập quyền rỗng
  // Output: 404 NOT_FOUND
  @Test
  void hiddenChannel404() throws Exception {
    allow();

    assertCode(post(channel, author, Map.of("content", "hi")), 404, "NOT_FOUND");
  }

  // Kịch bản: Community không trả lời
  // Input: checker ném DependencyUnavailableException
  // Output: 503 code DEPENDENCY_UNAVAILABLE, không lưu tin
  @Test
  void checkerDependencyDownReturns503WithCode() throws Exception {
    when(checker.check(any(), any()))
        .thenThrow(new DependencyUnavailableException("down", new RuntimeException()));

    assertCode(post(channel, author, Map.of("content", "hi")), 503, "DEPENDENCY_UNAVAILABLE");
    assertThat(countMessages()).isZero();
  }

  // Kịch bản: nội dung chỉ có khoảng trắng
  // Input: content "   "
  // Output: 400 VALIDATION_FAILED
  @Test
  void contentWithOnlySpacesIs400() throws Exception {
    assertCode(post(channel, author, Map.of("content", "   ")), 400, "VALIDATION_FAILED");
  }

  // Kịch bản: thiếu content
  // Input: body {} không có content
  // Output: 400 VALIDATION_FAILED
  @Test
  void missingContentIs400() throws Exception {
    assertCode(post(channel, author, Map.of("nonce", "n-x")), 400, "VALIDATION_FAILED");
  }

  // Kịch bản: 2001 emoji vượt giới hạn 2000 code point
  // Input: content 2001 emoji 👍
  // Output: 400 VALIDATION_FAILED
  @Test
  void contentOf2001EmojiIs400() throws Exception {
    assertCode(
        post(channel, author, Map.of("content", "👍".repeat(2001))), 400, "VALIDATION_FAILED");
  }

  // Kịch bản: đúng 2000 emoji (4000 char) hợp lệ
  // Input: content 2000 emoji 👍
  // Output: 201
  @Test
  void contentOf2000EmojiIs201() throws Exception {
    assertThat(post(channel, author, Map.of("content", "👍".repeat(2000))).statusCode())
        .isEqualTo(201);
  }

  // Kịch bản: mention lặp và mention rác
  // Input: "<@u1> <@u1> <@u2> <@abc>"
  // Output: message_mentions có đúng 2 dòng u1, u2
  @Test
  void mentionsAreStoredDeduped() throws Exception {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();

    HttpResponse<String> response =
        post(
            channel, author, Map.of("content", "<@" + u1 + "> <@" + u1 + "> <@" + u2 + "> <@abc>"));

    String id = MAPPER.readTree(response.body()).get("id").asText();
    assertThat(
            jdbc.queryForList(
                "select user_id from messaging.message_mentions where message_id = ?",
                UUID.class,
                UUID.fromString(id)))
        .containsExactlyInAnyOrder(u1, u2);
  }

  // Kịch bản: không gửi token
  // Input: POST không header Authorization
  // Output: 401
  @Test
  void noTokenIs401() throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(url(channel))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"hi\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  // Cấp đúng các quyền cho mọi user trên mọi kênh
  private void allow(ChannelPermission... permissions) {
    EnumSet<ChannelPermission> set = EnumSet.noneOf(ChannelPermission.class);
    set.addAll(List.of(permissions));
    when(checker.check(any(), any())).thenReturn(new ChannelAccess(serverId, set));
  }

  // Khẳng định status và code của Problem Details
  private void assertCode(HttpResponse<String> response, int status, String code) throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo(code);
  }

  // Đếm tin của tác giả mặc định
  private int countMessages() {
    return jdbc.queryForObject(
        "select count(*) from messaging.messages where author_id = ?", Integer.class, author);
  }

  // Payload các event message.created của một tin
  private List<String> outbox(String messageId) {
    return jdbc.queryForList(
        "select payload::text from messaging.outbox_event"
            + " where routing_key = 'message.created' and payload->>'messageId' = ?",
        String.class,
        messageId);
  }

  // URL gửi tin vào kênh
  private URI url(UUID channelId) {
    return URI.create("http://localhost:" + port + "/api/v1/channels/" + channelId + "/messages");
  }

  // POST body JSON vào kênh với Bearer của user
  private HttpResponse<String> post(UUID channelId, UUID user, Map<String, Object> body)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId))
            .header("Authorization", bearer(user))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

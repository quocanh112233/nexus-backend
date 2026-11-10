package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.ChannelAccess;
import com.nexus.messaging.application.port.ChannelPermissionChecker;
import com.nexus.messaging.domain.message.Message;
import com.nexus.messaging.domain.message.MessageRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra PATCH và DELETE tin trong kênh: sửa, xóa mềm, IDOR, outbox và khóa lạc quan
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class MessageEditDeleteIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private ChannelPermissionChecker checker;
  @MockitoSpyBean private MessageRepository messageRepository;

  private final UUID serverId = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID channel = UUID.randomUUID();

  // Cho mọi user đủ quyền xem và gửi tin trước mỗi test
  @BeforeEach
  void grantSend() {
    allow(ChannelPermission.VIEW_CHANNEL, ChannelPermission.SEND_MESSAGES);
  }

  // Kịch bản: tác giả sửa nội dung tin
  // Input: gửi "old" rồi PATCH {content:"new"}
  // Output: 200 nội dung mới, editedAt có giá trị, version 1, một dòng outbox message.edited
  @Test
  void editReturnsUpdatedContentAndEditedAt() throws Exception {
    String id = send(channel, "old");

    HttpResponse<String> response = patch(channel, id, author, "new");

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body.get("content").asText()).isEqualTo("new");
    assertThat(body.get("editedAt").isNull()).isFalse();
    assertThat(body.get("version").asLong()).isEqualTo(1);
    List<String> events = outbox("message.edited", id);
    assertThat(events).hasSize(1);
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertThat(payload.get("content").asText()).isEqualTo("new");
    assertThat(payload.get("serverId").asText()).isEqualTo(serverId.toString());
    assertThat(payload.get("editedAt").asText()).isEqualTo(body.get("editedAt").asText());
    assertThat(payload.get("version").asLong()).isEqualTo(1);
  }

  // Kịch bản: sửa tin với nội dung chứa ký tự NUL hoặc quá 8000 char
  // Input: gửi "old" rồi PATCH content "a\u0000b", rồi PATCH 8001 chữ a
  // Output: cả hai 400 VALIDATION_FAILED, nội dung trong DB vẫn là "old"
  @Test
  void editWithNulOrOversizedContentIs400() throws Exception {
    String id = send(channel, "old");

    HttpResponse<String> nul = patch(channel, id, author, "a\u0000b");
    HttpResponse<String> big = patch(channel, id, author, "a".repeat(8001));

    assertThat(nul.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(nul.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(big.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(big.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                "select content from messaging.messages where id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("old");
  }

  // Kịch bản: sửa với nội dung giống hệt
  // Input: gửi "same" rồi PATCH {content:"same"}
  // Output: 200 version 0, editedAt null, không có event message.edited
  @Test
  void sameContentEditChangesNothing() throws Exception {
    String id = send(channel, "same");

    JsonNode body = MAPPER.readTree(patch(channel, id, author, "same").body());

    assertThat(body.get("version").asLong()).isZero();
    assertThat(body.get("editedAt").isNull()).isTrue();
    assertThat(outbox("message.edited", id)).isEmpty();
  }

  // Kịch bản: nhiều mention trong lúc gửi và sửa
  // Input: gửi và sửa nội dung có 5 mention ngẫu nhiên theo thứ tự cho trước
  // Output: mentionUserIds trong phản hồi và payload message.edited giữ thứ tự xuất hiện
  @Test
  void mentionOrderFollowsContentOnSendAndEdit() throws Exception {
    List<String> sent = mentions(5);
    List<String> edited = mentions(5);
    JsonNode created = MAPPER.readTree(post(channel, author, Map.of("content", text(sent))).body());

    JsonNode body =
        MAPPER.readTree(patch(channel, created.get("id").asText(), author, text(edited)).body());

    assertThat(created.get("mentionUserIds"))
        .extracting(JsonNode::asText)
        .containsExactlyElementsOf(sent);
    assertThat(body.get("mentionUserIds"))
        .extracting(JsonNode::asText)
        .containsExactlyElementsOf(edited);
    JsonNode payload = MAPPER.readTree(outbox("message.edited", created.get("id").asText()).get(0));
    assertThat(payload.get("mentionUserIds"))
        .extracting(JsonNode::asText)
        .containsExactlyElementsOf(edited);
  }

  // Kịch bản: xóa mềm tin có mention
  // Input: gửi tin kèm mention rồi DELETE
  // Output: 204, DB content rỗng, deleted_at có giá trị, không còn dòng mention
  @Test
  void deletedMessageShowsNoContent() throws Exception {
    String id =
        MAPPER
            .readTree(
                post(channel, author, Map.of("content", "hi <@" + UUID.randomUUID() + ">")).body())
            .get("id")
            .asText();

    HttpResponse<String> response = delete(channel, id, author);

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select content from messaging.messages where id = ?",
                String.class,
                UUID.fromString(id)))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select deleted_at is not null from messaging.messages where id = ?",
                Boolean.class,
                UUID.fromString(id)))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.message_mentions where message_id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isZero();
  }

  // Kịch bản: xóa tin hai lần
  // Input: DELETE hai lần cùng tin bởi tác giả
  // Output: cả hai 204, đúng một dòng outbox message.deleted với deletedBy là tác giả
  @Test
  void deleteWritesOutboxMessageDeleted() throws Exception {
    String id = send(channel, "bye");

    int first = delete(channel, id, author).statusCode();
    int second = delete(channel, id, author).statusCode();

    assertThat(first).isEqualTo(204);
    assertThat(second).isEqualTo(204);
    List<String> events = outbox("message.deleted", id);
    assertThat(events).hasSize(1);
    JsonNode payload = MAPPER.readTree(events.get(0));
    assertThat(payload.get("deletedBy").asText()).isEqualTo(author.toString());
    assertThat(payload.get("channelId").asText()).isEqualTo(channel.toString());
    assertThat(payload.get("serverId").asText()).isEqualTo(serverId.toString());
  }

  // Kịch bản: IDOR, tin kênh A thao tác qua URL kênh B với đủ quyền ở B
  // Input: PATCH và DELETE tin của A qua kênh B bởi chính tác giả
  // Output: cả hai 404 NOT_FOUND, DB không đổi
  @Test
  void crossChannelMessageIdIs404() throws Exception {
    String id = send(channel, "secret");
    UUID other = UUID.randomUUID();
    allow(
        ChannelPermission.VIEW_CHANNEL,
        ChannelPermission.SEND_MESSAGES,
        ChannelPermission.MANAGE_MESSAGES);

    HttpResponse<String> patched = patch(other, id, author, "hacked");
    HttpResponse<String> deleted = delete(other, id, author);

    assertThat(patched.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(patched.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(deleted.statusCode()).isEqualTo(404);
    assertThat(
            jdbc.queryForMap(
                "select content, deleted_at, edited_at from messaging.messages where id = ?",
                UUID.fromString(id)))
        .containsEntry("content", "secret")
        .containsEntry("deleted_at", null)
        .containsEntry("edited_at", null);
  }

  // Kịch bản: người không phải tác giả sửa hoặc xóa khi không có MANAGE_MESSAGES
  // Input: user khác PATCH và DELETE tin của tác giả
  // Output: cả hai 403 PERMISSION_DENIED, tin còn nguyên
  @Test
  void nonAuthorIs403() throws Exception {
    String id = send(channel, "mine");
    UUID stranger = UUID.randomUUID();

    HttpResponse<String> patched = patch(channel, id, stranger, "x");
    HttpResponse<String> deleted = delete(channel, id, stranger);

    assertThat(patched.statusCode()).isEqualTo(403);
    assertThat(deleted.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(deleted.body()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(
            jdbc.queryForObject(
                "select content from messaging.messages where id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("mine");
  }

  // Kịch bản: sửa tin đã xóa mềm
  // Input: xóa tin rồi PATCH
  // Output: 404 NOT_FOUND
  @Test
  void editingDeletedMessageIs404() throws Exception {
    String id = send(channel, "bye");
    delete(channel, id, author);

    assertThat(patch(channel, id, author, "again").statusCode()).isEqualTo(404);
  }

  // Kịch bản: hai PATCH song song cùng đọc version cũ
  // Input: hai luồng PATCH nội dung khác nhau, cùng chờ nhau trước khi lưu
  // Output: đúng một 200 và một 409 VERSION_CONFLICT
  @Test
  void twoParallelEditsYieldOneOkAndOneConflict() throws Exception {
    String id = send(channel, "old");
    CyclicBarrier bothRead = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              bothRead.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(messageRepository)
        .save(any(Message.class));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (String text : new String[] {"alpha", "beta"}) {
        futures.add(pool.submit(() -> patch(channel, id, author, text)));
      }
      List<Integer> statuses = new ArrayList<>();
      HttpResponse<String> conflict = null;
      for (Future<HttpResponse<String>> f : futures) {
        HttpResponse<String> r = f.get();
        statuses.add(r.statusCode());
        if (r.statusCode() == 409) {
          conflict = r;
        }
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
      assertThat(MAPPER.readTree(conflict.body()).get("code").asText())
          .isEqualTo("VERSION_CONFLICT");
      assertThat(outbox("message.edited", id)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  // Cấp đúng các quyền cho mọi user trên mọi kênh
  private void allow(ChannelPermission... permissions) {
    EnumSet<ChannelPermission> set = EnumSet.noneOf(ChannelPermission.class);
    set.addAll(List.of(permissions));
    when(checker.check(any(), any())).thenReturn(new ChannelAccess(serverId, set));
  }

  // Sinh n chuỗi UUID mention ngẫu nhiên
  private static List<String> mentions(int n) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(UUID.randomUUID().toString());
    }
    return ids;
  }

  // Ghép các mention thành nội dung tin theo đúng thứ tự
  private static String text(List<String> ids) {
    return ids.stream().map(i -> "<@" + i + ">").reduce("hi", (a, b) -> a + " " + b);
  }

  // Gửi tin bằng tác giả mặc định và trả id
  private String send(UUID channelId, String content) throws Exception {
    return MAPPER
        .readTree(post(channelId, author, Map.of("content", content)).body())
        .get("id")
        .asText();
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

  // URL gốc tin của kênh
  private URI url(UUID channelId, String suffix) {
    return URI.create(
        "http://localhost:" + port + "/api/v1/channels/" + channelId + "/messages" + suffix);
  }

  // POST body JSON vào kênh với Bearer của user
  private HttpResponse<String> post(UUID channelId, UUID user, Map<String, Object> body)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId, ""))
            .header("Authorization", bearer(user))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // PATCH nội dung mới của tin với Bearer của user
  private HttpResponse<String> patch(UUID channelId, String id, UUID user, String content)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId, "/" + id))
            .header("Authorization", bearer(user))
            .header("Content-Type", "application/json")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(Map.of("content", content))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // DELETE tin với Bearer của user
  private HttpResponse<String> delete(UUID channelId, String id, UUID user) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(url(channelId, "/" + id))
            .header("Authorization", bearer(user))
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

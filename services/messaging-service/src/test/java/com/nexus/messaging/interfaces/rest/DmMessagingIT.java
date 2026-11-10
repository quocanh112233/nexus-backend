package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.domain.channel.ChannelRefRepository;
import com.nexus.messaging.domain.shared.ChannelId;
import com.nexus.messaging.domain.shared.UserId;
import com.nexus.messaging.domain.social.DmChannel;
import com.nexus.messaging.domain.social.DmChannelRepository;
import com.nexus.messaging.domain.social.FriendshipRepository;
import com.nexus.messaging.domain.social.UserPair;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra REST trên kênh DM với chuỗi checker thật: quyền theo bạn bè, không qua cache quyền
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class DmMessagingIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String THUMBS_UP = "%F0%9F%91%8D";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private TransactionTemplate tx;
  @Autowired private DmChannelRepository dms;
  @Autowired private ChannelRefRepository channelRefs;
  @Autowired private FriendshipRepository friendships;
  @LocalServerPort private int port;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final UUID dm = UUID.randomUUID();

  // Tạo kênh DM alice-bob trong dm_channels và channel_refs, hai người đang là bạn
  @BeforeEach
  void openDmBetweenFriends() {
    UserPair pair = UserPair.of(new UserId(alice), new UserId(bob));
    tx.executeWithoutResult(
        s -> {
          dms.insertIfAbsent(DmChannel.rehydrate(dm, pair, Instant.now()));
          channelRefs.upsertDmChannel(new ChannelId(dm));
          friendships.insertIfAbsent(pair, Instant.now());
        });
  }

  // Kịch bản: bạn bè gửi, sửa, xóa tin của mình trong DM
  // Input: alice POST, PATCH, DELETE tin của mình
  // Output: 201/200/204, ba outbox có khóa serverId mang JSON null, không ghi khóa perm:* nào
  @Test
  void friendsMessageWithNullServerIdAndNoPermCache() throws Exception {
    HttpResponse<String> sent = post(alice, "hi");
    String id = MAPPER.readTree(sent.body()).get("id").asText();

    assertThat(sent.statusCode()).isEqualTo(201);
    assertThat(call("PATCH", messages("/" + id), alice, Map.of("content", "new")).statusCode())
        .isEqualTo(200);
    assertThat(call("DELETE", messages("/" + id), alice, null).statusCode()).isEqualTo(204);

    for (String key : List.of("message.created", "message.edited", "message.deleted")) {
      JsonNode payload = MAPPER.readTree(outbox(key, id));
      assertThat(payload.has("serverId")).isTrue();
      assertThat(payload.get("serverId").isNull()).isTrue();
    }
    assertThat(redis.hasKey("perm:" + dm + ":" + alice)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select kind from messaging.channel_refs where channel_id = ?", String.class, dm))
        .isEqualTo("DM");
  }

  // Kịch bản: hủy kết bạn sau khi đã gửi tin, cache có sẵn entry cho phép mọi quyền
  // Input: alice gửi, bob thả reaction; seed perm:* "|63|0" cho cả hai; alice DELETE /friends/bob
  // Output: gửi, reaction, chữ ký đính kèm 403 ngay; GET messages vẫn 200 cho cả hai
  @Test
  void unfriendRevokesWritesImmediatelyDespiteWarmCache() throws Exception {
    String id = MAPPER.readTree(post(alice, "before").body()).get("id").asText();
    assertThat(reaction(id, bob).statusCode()).isEqualTo(204);
    seedStaleCache(alice);
    seedStaleCache(bob);

    assertThat(call("DELETE", "/api/v1/friends/" + bob, alice, null).statusCode()).isEqualTo(204);

    HttpResponse<String> send = post(alice, "after");
    assertThat(send.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(send.body()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
    assertThat(reaction(id, bob).statusCode()).isEqualTo(403);
    assertThat(
            call("POST", "/api/v1/attachments/signature", alice, Map.of("channelId", dm.toString()))
                .statusCode())
        .isEqualTo(403);
    for (UUID user : List.of(alice, bob)) {
      HttpResponse<String> list = call("GET", messages(""), user, null);
      assertThat(list.statusCode()).isEqualTo(200);
      assertThat(list.body()).contains(id);
    }
  }

  // Kịch bản: tác giả sửa tin của mình trong DM trước và sau khi hủy kết bạn
  // Input: alice gửi, PATCH khi còn là bạn, alice DELETE /friends/bob rồi PATCH lại và DELETE tin
  // Output: PATCH đầu 200, PATCH sau unfriend 403 nội dung giữ nguyên, DELETE tin vẫn 204
  @Test
  void unfriendBlocksEditingOwnMessageButNotDeleting() throws Exception {
    String id = MAPPER.readTree(post(alice, "v1").body()).get("id").asText();
    assertThat(call("PATCH", messages("/" + id), alice, Map.of("content", "v2")).statusCode())
        .isEqualTo(200);

    call("DELETE", "/api/v1/friends/" + bob, alice, null);

    assertThat(call("PATCH", messages("/" + id), alice, Map.of("content", "v3")).statusCode())
        .isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "select content from messaging.messages where id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("v2");
    assertThat(call("DELETE", messages("/" + id), alice, null).statusCode()).isEqualTo(204);
  }

  // Kịch bản: người ngoài DM, kể cả khi cache có entry cho phép mọi quyền
  // Input: seed perm:* "|63|0" cho stranger, stranger GET và POST messages
  // Output: cả hai 404 NOT_FOUND, không lộ sự tồn tại của kênh
  @Test
  void strangerGetsNotFound() throws Exception {
    UUID stranger = UUID.randomUUID();
    seedStaleCache(stranger);

    HttpResponse<String> get = call("GET", messages(""), stranger, null);
    HttpResponse<String> send = post(stranger, "hi");

    assertThat(get.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(get.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(send.statusCode()).isEqualTo(404);
  }

  // Kịch bản: người tham gia sửa hoặc xóa tin của người kia, cache có entry kèm MANAGE_MESSAGES
  // Input: alice gửi, seed perm:* "|63|0" cho bob, bob PATCH và DELETE tin của alice
  // Output: cả hai 403 vì DM không có MANAGE_MESSAGES, tin còn nguyên
  @Test
  void participantCannotEditOrDeleteOthersMessage() throws Exception {
    String id = MAPPER.readTree(post(alice, "mine").body()).get("id").asText();
    seedStaleCache(bob);

    HttpResponse<String> patched = call("PATCH", messages("/" + id), bob, Map.of("content", "x"));
    HttpResponse<String> deleted = call("DELETE", messages("/" + id), bob, null);

    assertThat(patched.statusCode()).isEqualTo(403);
    assertThat(deleted.statusCode()).isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "select content from messaging.messages where id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo("mine");
  }

  // Ghi entry cache quyền giả cho phép mọi quyền, nếu DM đọc cache thì test sẽ lộ
  private void seedStaleCache(UUID user) {
    redis.opsForValue().set("perm:" + dm + ":" + user, UUID.randomUUID() + "|63|0");
  }

  // Payload outbox duy nhất theo routing key của một tin
  private String outbox(String routingKey, String messageId) {
    return jdbc.queryForObject(
        "select payload::text from messaging.outbox_event"
            + " where routing_key = ? and payload->>'messageId' = ?",
        String.class,
        routingKey,
        messageId);
  }

  // Đường dẫn tin của kênh DM kèm hậu tố
  private String messages(String suffix) {
    return "/api/v1/channels/" + dm + "/messages" + suffix;
  }

  // POST tin có nội dung vào kênh DM
  private HttpResponse<String> post(UUID user, String content) throws Exception {
    return call("POST", messages(""), user, Map.of("content", content));
  }

  // PUT reaction 👍 lên tin
  private HttpResponse<String> reaction(String id, UUID user) throws Exception {
    return call("PUT", messages("/" + id + "/reactions/" + THUMBS_UP), user, null);
  }

  // Gửi request với Bearer của user, body JSON nếu có
  private HttpResponse<String> call(String method, String path, UUID user, Object body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer(user));
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}

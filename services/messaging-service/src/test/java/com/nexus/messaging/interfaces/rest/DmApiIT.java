package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.UserDirectory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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

// Kiểm tra REST mở và liệt kê DM: tạo một lần, mở đồng thời, quyền bạn bè, nhắn trong DM đã mở
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class DmApiIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private UserDirectory directory;

  // Identity giả: mọi user đều tồn tại để kết bạn qua API
  @BeforeEach
  void identityKnowsEveryone() {
    when(directory.exists(any())).thenReturn(true);
  }

  // Kịch bản: mở DM lần đầu rồi lần hai giữa hai bạn
  // Input: POST /dms từ a rồi từ b, GET /dms của a và b
  // Output: 201 rồi 200 cùng channelId, đúng một outbox, một dm_channels, channel_refs kind DM
  @Test
  void firstOpenCreatesSecondReturnsSame() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    befriend(a, b);

    HttpResponse<String> first = open(a, b);
    HttpResponse<String> second = open(b, a);

    JsonNode created = MAPPER.readTree(first.body());
    String channelId = created.get("channelId").asText();
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(created.get("friendId").asText()).isEqualTo(b.toString());
    assertThat(created.get("createdAt").asText()).isNotBlank();
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(second.body()).get("channelId").asText()).isEqualTo(channelId);
    assertThat(MAPPER.readTree(second.body()).get("friendId").asText()).isEqualTo(a.toString());
    assertFinalState(a, b, channelId);
    assertThat(ids(call("GET", "/dms", a, null), "channelId")).containsExactly(channelId);
    assertThat(ids(call("GET", "/dms", b, null), "friendId")).containsExactly(a.toString());
  }

  // Kịch bản: hai bên mở DM đồng thời, lặp 5 vòng với cặp user mới
  // Input: hai luồng POST /dms chéo chiều qua barrier
  // Output: mã {200,201} không 500, cùng channelId, đúng một outbox, dm_channels, ref DM
  @Test
  void simultaneousOpensShareOneChannel() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 5; i++) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        befriend(a, b);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        for (UUID[] p : new UUID[][] {{a, b}, {b, a}}) {
          results.add(
              pool.submit(
                  () -> {
                    barrier.await();
                    return open(p[0], p[1]);
                  }));
        }
        HttpResponse<String> r1 = results.get(0).get();
        HttpResponse<String> r2 = results.get(1).get();

        assertThat(List.of(r1.statusCode(), r2.statusCode())).containsExactlyInAnyOrder(200, 201);
        String c1 = MAPPER.readTree(r1.body()).get("channelId").asText();
        assertThat(MAPPER.readTree(r2.body()).get("channelId").asText()).isEqualTo(c1);
        assertFinalState(a, b, c1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // Kịch bản: mở DM với người lạ và với chính mình
  // Input: POST /dms khi chưa là bạn; POST /dms friendId là mình; body rỗng
  // Output: 403 NOT_FRIENDS và không có dm_channels; 400; 400
  @Test
  void strangerSelfAndInvalidBody() throws Exception {
    UUID a = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();

    HttpResponse<String> notFriends = open(a, stranger);
    assertThat(notFriends.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(notFriends.body()).get("code").asText()).isEqualTo("NOT_FRIENDS");
    assertThat(dmRows(a, stranger)).isZero();
    assertThat(open(a, a).statusCode()).isEqualTo(400);
    assertThat(call("POST", "/dms", a, "{}").statusCode()).isEqualTo(400);
  }

  // Kịch bản: hủy kết bạn rồi mở lại, sau đó kết bạn lại
  // Input: mở DM, a DELETE /friends/b, mở lại, GET /dms, kết bạn lại, mở lại
  // Output: 403 NOT_FRIENDS và GET vẫn liệt kê DM cũ; sau kết bạn lại 200 đúng channelId cũ
  @Test
  void unfriendBlocksReopenButKeepsListAndRefriendReusesChannel() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    befriend(a, b);
    String channelId = MAPPER.readTree(open(a, b).body()).get("channelId").asText();
    assertThat(call("DELETE", "/friends/" + b, a, null).statusCode()).isEqualTo(204);

    HttpResponse<String> reopen = open(a, b);

    assertThat(reopen.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(reopen.body()).get("code").asText()).isEqualTo("NOT_FRIENDS");
    assertThat(ids(call("GET", "/dms", a, null), "channelId")).containsExactly(channelId);
    assertThat(ids(call("GET", "/dms", b, null), "channelId")).containsExactly(channelId);
    befriend(b, a);
    HttpResponse<String> again = open(b, a);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(again.body()).get("channelId").asText()).isEqualTo(channelId);
    assertFinalState(a, b, channelId);
  }

  // Kịch bản: kênh DM vừa mở dùng được cho nhắn tin của Task 5
  // Input: friend POST message vào channelId, người lạ POST cùng kênh
  // Output: friend 201, người lạ 404
  @Test
  void openedDmAcceptsMessagesFromFriendOnly() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    befriend(a, b);
    String channelId = MAPPER.readTree(open(a, b).body()).get("channelId").asText();
    String path = "/channels/" + channelId + "/messages";
    String body = MAPPER.writeValueAsString(Map.of("content", "hi"));

    assertThat(call("POST", path, b, body).statusCode()).isEqualTo(201);
    assertThat(call("POST", path, UUID.randomUUID(), body).statusCode()).isEqualTo(404);
  }

  // Kịch bản: gọi không kèm JWT
  // Input: POST và GET /dms không header Authorization
  // Output: cả hai 401
  @Test
  void noJwtIs401() throws Exception {
    for (String method : List.of("POST", "GET")) {
      HttpResponse<String> res =
          HTTP.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/dms"))
                  .header("Content-Type", "application/json")
                  .method(
                      method,
                      "POST".equals(method)
                          ? HttpRequest.BodyPublishers.ofString("{}")
                          : HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(res.statusCode()).isEqualTo(401);
    }
  }

  // Khẳng định trạng thái cuối: một dm_channels, một outbox DmChannelCreated, channel_refs DM
  private void assertFinalState(UUID a, UUID b, String channelId) {
    assertThat(dmRows(a, b)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.outbox_event"
                    + " where event_type = 'DmChannelCreated'"
                    + " and payload->'participantIds' @> to_jsonb(?::text)"
                    + " and payload->'participantIds' @> to_jsonb(?::text)",
                Integer.class,
                a.toString(),
                b.toString()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from messaging.outbox_event"
                    + " where event_type = 'DmChannelCreated' and aggregate_id = ?",
                Integer.class,
                UUID.fromString(channelId)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select kind from messaging.channel_refs where channel_id = ?",
                String.class,
                UUID.fromString(channelId)))
        .isEqualTo("DM");
  }

  // Số dòng dm_channels giữa hai user, bất kể thứ tự
  private int dmRows(UUID a, UUID b) {
    return jdbc.queryForObject(
        "select count(*) from messaging.dm_channels"
            + " where user_low_id in (?, ?) and user_high_id in (?, ?)",
        Integer.class,
        a,
        b,
        a,
        b);
  }

  // Kết bạn qua API: from mời, to chấp nhận
  private void befriend(UUID from, UUID to) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of("targetUserId", to));
    String id =
        MAPPER.readTree(call("POST", "/friends/requests", from, body).body()).get("id").asText();
    assertThat(call("POST", "/friends/requests/" + id + "/accept", to, null).statusCode())
        .isEqualTo(200);
  }

  // POST /dms mở DM của user với friend
  private HttpResponse<String> open(UUID user, UUID friend) throws Exception {
    return call("POST", "/dms", user, MAPPER.writeValueAsString(Map.of("friendId", friend)));
  }

  // Giá trị trường field của từng phần tử trong items
  private static List<String> ids(HttpResponse<String> response, String field) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode n : MAPPER.readTree(response.body()).get("items")) {
      out.add(n.get(field).asText());
    }
    return out;
  }

  // Gọi API với Bearer của user, body null thì không gửi body
  private HttpResponse<String> call(String method, String path, UUID user, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer(user));
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

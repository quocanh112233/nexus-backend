package com.nexus.messaging.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.messaging.MessagingIntegrationTest;
import com.nexus.messaging.application.port.UserDirectory;
import com.nexus.messaging.domain.shared.UserId;
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

// Kiểm tra REST bạn bè và lời mời: luồng gửi/nhận, quyền, 404, 400, 401 và đua mời hai chiều
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class FriendApiIT extends MessagingIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;
  @MockitoBean private UserDirectory directory;

  // Identity giả: mọi user đều tồn tại
  @BeforeEach
  void identityKnowsEveryone() {
    when(directory.exists(any())).thenReturn(true);
  }

  // Kịch bản: A mời B, B nhận, B chấp nhận
  // Input: POST requests, GET incoming/outgoing, accept bởi A rồi bởi B, GET /friends
  // Output: 201 PENDING, hai danh sách đúng, A accept 403, B accept 200, cả hai thấy nhau
  @Test
  void sendListAcceptFlow() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    HttpResponse<String> sent = call("POST", "/friends/requests", a, target(b));
    JsonNode req = MAPPER.readTree(sent.body());
    String id = req.get("id").asText();

    assertThat(sent.statusCode()).isEqualTo(201);
    assertThat(req.get("status").asText()).isEqualTo("PENDING");
    assertThat(ids(call("GET", "/friends/requests", b, null), "id")).containsExactly(id);
    assertThat(ids(call("GET", "/friends/requests?direction=outgoing", a, null), "id"))
        .containsExactly(id);
    assertThat(call("GET", "/friends/requests", a, null).body()).contains("\"items\":[]");
    HttpResponse<String> byRequester = call("POST", "/friends/requests/" + id + "/accept", a, null);
    assertThat(byRequester.statusCode()).isEqualTo(403);
    assertThat(MAPPER.readTree(byRequester.body()).get("code").asText())
        .isEqualTo("PERMISSION_DENIED");
    assertThat(call("POST", "/friends/requests/" + id + "/accept", b, null).statusCode())
        .isEqualTo(200);
    assertThat(ids(call("GET", "/friends", a, null), "userId")).containsExactly(b.toString());
    assertThat(ids(call("GET", "/friends", b, null), "userId")).containsExactly(a.toString());
  }

  // Kịch bản: B từ chối rồi A mời lại; mời trùng; mời khi đã là bạn
  // Input: decline, accept lần hai, mời lại, mời lần nữa, accept rồi mời nữa
  // Output: decline 200, accept sau đó 409 VERSION_CONFLICT, mời trùng 409, đã bạn 409
  @Test
  void conflictsMapTo409() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String id = id(call("POST", "/friends/requests", a, target(b)));

    HttpResponse<String> dup = call("POST", "/friends/requests", a, target(b));
    assertThat(dup.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(dup.body()).get("code").asText()).isEqualTo("FRIEND_REQUEST_EXISTS");
    assertThat(call("POST", "/friends/requests/" + id + "/decline", b, null).statusCode())
        .isEqualTo(200);
    HttpResponse<String> late = call("POST", "/friends/requests/" + id + "/accept", b, null);
    assertThat(late.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(late.body()).get("code").asText()).isEqualTo("VERSION_CONFLICT");

    String again = id(call("POST", "/friends/requests", a, target(b)));
    call("POST", "/friends/requests/" + again + "/accept", b, null);
    HttpResponse<String> friends = call("POST", "/friends/requests", a, target(b));
    assertThat(friends.statusCode()).isEqualTo(409);
    assertThat(MAPPER.readTree(friends.body()).get("code").asText()).isEqualTo("ALREADY_FRIENDS");
  }

  // Kịch bản: B có lời mời chờ tới A, A mời ngược lại
  // Input: B mời A rồi A mời B
  // Output: lần hai 200 ACCEPTED, hai bên là bạn
  @Test
  void reverseInviteAutoAccepts() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    call("POST", "/friends/requests", b, target(a));

    HttpResponse<String> res = call("POST", "/friends/requests", a, target(b));

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(res.body()).get("status").asText()).isEqualTo("ACCEPTED");
    assertThat(ids(call("GET", "/friends", a, null), "userId")).containsExactly(b.toString());
  }

  // Kịch bản: A hủy lời mời, người ngoài hủy hoặc accept lời mời của người khác
  // Input: DELETE request bởi A, bởi C; accept bởi C
  // Output: A 204, C 404 NOT_FOUND, accept bởi C 404
  @Test
  void cancelAndUnrelatedUser() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    String id = id(call("POST", "/friends/requests", a, target(b)));

    HttpResponse<String> byOutsider = call("DELETE", "/friends/requests/" + id, c, null);
    assertThat(byOutsider.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(byOutsider.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(call("POST", "/friends/requests/" + id + "/accept", c, null).statusCode())
        .isEqualTo(404);
    assertThat(call("DELETE", "/friends/requests/" + id, a, null).statusCode()).isEqualTo(204);
    assertThat(call("GET", "/friends/requests?direction=outgoing", a, null).body())
        .contains("\"items\":[]");
  }

  // Kịch bản: gửi cho chính mình, target không tồn tại, thiếu target, id sai định dạng
  // Input: POST target=mình; target lạ; body rỗng; DELETE /friends/xyz
  // Output: 400, 404 NOT_FOUND, 400, 400
  @Test
  void invalidInputs() throws Exception {
    UUID a = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    when(directory.exists(new UserId(stranger))).thenReturn(false);

    assertThat(call("POST", "/friends/requests", a, target(a)).statusCode()).isEqualTo(400);
    HttpResponse<String> unknown = call("POST", "/friends/requests", a, target(stranger));
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(unknown.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    assertThat(call("POST", "/friends/requests", a, "{}").statusCode()).isEqualTo(400);
    assertThat(call("DELETE", "/friends/xyz", a, null).statusCode()).isEqualTo(400);
  }

  // Kịch bản: hủy kết bạn hai lần
  // Input: kết bạn A-B rồi A DELETE /friends/B hai lần
  // Output: lần một 204 và B không còn trong danh sách A, lần hai 404 NOT_FOUND
  @Test
  void removeFriendTwice() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String id = id(call("POST", "/friends/requests", a, target(b)));
    call("POST", "/friends/requests/" + id + "/accept", b, null);

    assertThat(call("DELETE", "/friends/" + b, a, null).statusCode()).isEqualTo(204);
    assertThat(call("GET", "/friends", a, null).body()).contains("\"items\":[]");
    HttpResponse<String> second = call("DELETE", "/friends/" + b, a, null);
    assertThat(second.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(second.body()).get("code").asText()).isEqualTo("NOT_FOUND");
  }

  // Kịch bản: gọi không kèm JWT
  // Input: GET /friends không header Authorization
  // Output: 401
  @Test
  void noJwtIs401() throws Exception {
    HttpResponse<String> res =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/friends"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(res.statusCode()).isEqualTo(401);
  }

  // Kịch bản: A và B mời nhau cùng lúc, lặp 5 lần với cặp user mới
  // Input: hai luồng POST requests chéo chiều qua barrier
  // Output: mỗi vòng đúng {200,201}, đúng 1 friendship và không còn lời mời PENDING của cặp
  @Test
  void simultaneousCrossInvitesNeverFailWith500() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 5; i++) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID[] p : new UUID[][] {{a, b}, {b, a}}) {
          results.add(
              pool.submit(
                  () -> {
                    barrier.await();
                    return call("POST", "/friends/requests", p[0], target(p[1])).statusCode();
                  }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
          statuses.add(f.get());
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 201);
        assertThat(friendships(a, b)).isEqualTo(1);
        assertThat(pendingRequests(a, b)).isZero();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // Số dòng friendships giữa hai user, bất kể thứ tự
  private int friendships(UUID a, UUID b) {
    return jdbc.queryForObject(
        "select count(*) from messaging.friendships"
            + " where user_low_id in (?, ?) and user_high_id in (?, ?)",
        Integer.class,
        a,
        b,
        a,
        b);
  }

  // Số lời mời PENDING giữa hai user, bất kể chiều
  private int pendingRequests(UUID a, UUID b) {
    return jdbc.queryForObject(
        "select count(*) from messaging.friend_requests where status = 'PENDING'"
            + " and requester_id in (?, ?) and addressee_id in (?, ?)",
        Integer.class,
        a,
        b,
        a,
        b);
  }

  // Body JSON gửi lời mời tới user
  private static String target(UUID user) {
    return MAPPER.writeValueAsString(Map.of("targetUserId", user));
  }

  // Id của lời mời trong phản hồi
  private static String id(HttpResponse<String> response) throws Exception {
    return MAPPER.readTree(response.body()).get("id").asText();
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

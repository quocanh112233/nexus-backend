package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.domain.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra các endpoint hồ sơ /api/v1/users với Postgres thật qua HTTP trên cổng ngẫu nhiên
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserControllerIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String PASSWORD = "secret-pass-1";

  private static volatile CyclicBarrier readBarrier;

  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private UserRepository userRepository;
  @LocalServerPort private int port;

  // Kịch bản: không có Bearer thì mọi endpoint hồ sơ bị chặn
  // Input: GET /users/me và PATCH /users/me không có header Authorization
  // Output: cả hai 401 UNAUTHENTICATED
  @Test
  void meRequiresBearer() throws Exception {
    HttpResponse<String> get = send("GET", "/api/v1/users/me", null, null);
    HttpResponse<String> patch = send("PATCH", "/api/v1/users/me", "{}", null);

    for (HttpResponse<String> response : List.of(get, patch)) {
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("UNAUTHENTICATED");
    }
  }

  // Kịch bản: PATCH chỉ đổi field có mặt và ghi dòng outbox user.profile-updated mỗi lần
  // Input: PATCH displayName+aboutMe rồi PATCH chỉ aboutMe, sau đó GET /me
  // Output: 200 cả hai, displayName giữ nguyên lần hai, 2 dòng outbox User, payload mới nhất
  @Test
  void patchMeUpdatesProfileAndWritesOutboxRow() throws Exception {
    String tag = tag();
    String id = register(tag);
    String token = login(tag);

    HttpResponse<String> first =
        send(
            "PATCH",
            "/api/v1/users/me",
            "{\"displayName\":\"New Name\",\"aboutMe\":\"hi\"}",
            token);
    HttpResponse<String> second =
        send("PATCH", "/api/v1/users/me", "{\"aboutMe\":\"second\"}", token);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(first.body()).get("displayName").asText()).isEqualTo("New Name");
    assertThat(second.statusCode()).isEqualTo(200);
    JsonNode me = MAPPER.readTree(send("GET", "/api/v1/users/me", null, token).body());
    assertThat(me.get("displayName").asText()).isEqualTo("New Name");
    assertThat(me.get("aboutMe").asText()).isEqualTo("second");
    assertThat(me.get("email").asText()).isEqualTo(tag + "@x.io");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select aggregate_type, event_type, payload::text as payload from identity.outbox_event"
                + " where routing_key = 'user.profile-updated' and aggregate_id = ?::uuid"
                + " order by id",
            id);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("aggregate_type", "User"));
    assertThat(rows)
        .allSatisfy(r -> assertThat(r).containsEntry("event_type", "UserProfileUpdated"));
    JsonNode payload = MAPPER.readTree((String) rows.get(1).get("payload"));
    assertThat(payload.get("userId").asText()).isEqualTo(id);
    assertThat(payload.get("displayName").asText()).isEqualTo("New Name");
    assertThat(payload.get("aboutMe").asText()).isEqualTo("second");
    assertThat(payload.get("avatarUrl").isNull()).isTrue();
  }

  // Kịch bản: aboutMe vượt 190 ký tự bị chặn ở biên REST
  // Input: PATCH aboutMe 191 ký tự
  // Output: 400 VALIDATION_FAILED có lỗi ở field aboutMe
  @Test
  void patchWithTooLongAboutMeReturns400() throws Exception {
    String tag = tag();
    register(tag);

    HttpResponse<String> response =
        send("PATCH", "/api/v1/users/me", "{\"aboutMe\":\"" + "x".repeat(191) + "\"}", login(tag));

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("aboutMe");
  }

  // Kịch bản: hồ sơ công khai không có email và id lạ hoặc sai định dạng bị từ chối đúng mã
  // Input: GET /users/{id} của user khác; id không tồn tại; id không phải UUID
  // Output: 200 chỉ có id, username, displayName, avatarUrl, aboutMe; sau đó 404 NOT_FOUND và 400
  @Test
  void publicProfileHidesEmail() throws Exception {
    String tag = tag();
    String id = register(tag);
    String viewer = tag();
    register(viewer);

    HttpResponse<String> response = send("GET", "/api/v1/users/" + id, null, login(viewer));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("id", "username", "displayName", "avatarUrl", "aboutMe");
    assertThat(response.body()).doesNotContain("email").doesNotContain("@x.io");
    String token = login(viewer);
    HttpResponse<String> missing = send("GET", "/api/v1/users/" + UUID.randomUUID(), null, token);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(missing.body()).get("code").asText()).isEqualTo("NOT_FOUND");
    HttpResponse<String> bad = send("GET", "/api/v1/users/not-a-uuid", null, token);
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(MAPPER.readTree(bad.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  // Kịch bản: tra lô giới hạn 100 id, từ chối id sai định dạng và chấp nhận rỗng
  // Input: ids có 101 UUID; ids có "abc"; ids= rỗng; không có tham số ids
  // Output: 400 VALIDATION_FAILED; 400 VALIDATION_FAILED; 200 []; 200 []
  @Test
  void batchLookupRejectsMoreThan100AndBadUuidAndAcceptsEmpty() throws Exception {
    String tag = tag();
    register(tag);
    String token = login(tag);
    String tooMany =
        String.join(
            ",",
            java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                .limit(101)
                .toList());

    HttpResponse<String> many = send("GET", "/api/v1/users?ids=" + tooMany, null, token);
    HttpResponse<String> bad = send("GET", "/api/v1/users?ids=abc", null, token);
    HttpResponse<String> empty = send("GET", "/api/v1/users?ids=", null, token);
    HttpResponse<String> absent = send("GET", "/api/v1/users", null, token);

    for (HttpResponse<String> response : List.of(many, bad)) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(MAPPER.readTree(response.body()).get("code").asText())
          .isEqualTo("VALIDATION_FAILED");
    }
    for (HttpResponse<String> response : List.of(empty, absent)) {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(MAPPER.readTree(response.body()).isEmpty()).isTrue();
    }
  }

  // Kịch bản: tra lô gộp id trùng và bỏ qua id không tồn tại
  // Input: ids=a,a,b,c với c không tồn tại
  // Output: 200 gồm đúng 2 hồ sơ a và b, không có email
  @Test
  void batchLookupDeduplicatesIds() throws Exception {
    String tagA = tag();
    String tagB = tag();
    String a = register(tagA);
    String b = register(tagB);
    String token = login(tagA);

    HttpResponse<String> response =
        send(
            "GET",
            "/api/v1/users?ids=" + a + "," + a + "," + b + "," + UUID.randomUUID(),
            null,
            token);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.valueStream().map(n -> n.get("id").asText()).toList())
        .containsExactlyInAnyOrder(a, b);
    assertThat(response.body()).doesNotContain("email");
  }

  // Kịch bản: tra theo username không phân biệt hoa thường
  // Input: GET /users/by-username/ với username viết hoa; username không tồn tại
  // Output: 200 đúng user, không có email; 404 NOT_FOUND
  @Test
  void byUsernameIsCaseInsensitive() throws Exception {
    String tag = tag();
    String id = register(tag);
    String token = login(tag);

    HttpResponse<String> response =
        send("GET", "/api/v1/users/by-username/" + tag.toUpperCase(), null, token);
    HttpResponse<String> missing = send("GET", "/api/v1/users/by-username/" + tag(), null, token);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).get("id").asText()).isEqualTo(id);
    assertThat(response.body()).doesNotContain("email");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(missing.body()).get("code").asText()).isEqualTo("NOT_FOUND");
  }

  // Kịch bản: hai PATCH song song cùng đọc một phiên bản thì một cái thua với 409
  // Input: 2 thread PATCH aboutMe khác nhau, repository chờ cả hai đọc xong mới cho lưu
  // Output: statuses 200 và 409 VERSION_CONFLICT, chỉ 1 dòng outbox user.profile-updated
  @Test
  void concurrentPatchesOneGets409VersionConflict() throws Exception {
    String tag = tag();
    String id = register(tag);
    String token = login(tag);
    CyclicBarrier barrier = new CyclicBarrier(2);
    doAnswer(
            inv -> {
              Object found = inv.callRealMethod();
              CyclicBarrier current = readBarrier;
              if (current != null) {
                current.await(10, TimeUnit.SECONDS);
              }
              return found;
            })
        .when(userRepository)
        .findById(any());
    readBarrier = barrier;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<HttpResponse<String>>> tasks =
          List.of(
              () -> send("PATCH", "/api/v1/users/me", "{\"aboutMe\":\"one\"}", token),
              () -> send("PATCH", "/api/v1/users/me", "{\"aboutMe\":\"two\"}", token));
      List<Future<HttpResponse<String>>> futures = tasks.stream().map(pool::submit).toList();
      HttpResponse<String> r1 = futures.get(0).get();
      HttpResponse<String> r2 = futures.get(1).get();

      assertThat(List.of(r1.statusCode(), r2.statusCode())).containsExactlyInAnyOrder(200, 409);
      HttpResponse<String> loser = r1.statusCode() == 409 ? r1 : r2;
      assertThat(MAPPER.readTree(loser.body()).get("code").asText()).isEqualTo("VERSION_CONFLICT");
      Integer rows =
          jdbc.queryForObject(
              "select count(*) from identity.outbox_event where routing_key ="
                  + " 'user.profile-updated' and aggregate_id = ?::uuid",
              Integer.class,
              id);
      assertThat(rows).isEqualTo(1);
    } finally {
      readBarrier = null;
      pool.shutdownNow();
    }
  }

  // Sinh chuỗi ngẫu nhiên hợp lệ cho username và phần đầu email
  private static String tag() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Đăng ký user tag@x.io và trả id của user
  private String register(String tag) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/auth/register",
            MAPPER.writeValueAsString(
                Map.of("email", tag + "@x.io", "username", tag, "password", PASSWORD)),
            null);
    assertThat(response.statusCode()).isEqualTo(201);
    return MAPPER.readTree(response.body()).get("id").asText();
  }

  // Đăng nhập user tag@x.io và trả access token
  private String login(String tag) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/v1/auth/login",
            MAPPER.writeValueAsString(Map.of("email", tag + "@x.io", "password", PASSWORD)),
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body()).get("accessToken").asText();
  }

  // Gửi request JSON tới ứng dụng, body và Bearer khác null thì mới gửi kèm
  private HttpResponse<String> send(String method, String path, String json, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}

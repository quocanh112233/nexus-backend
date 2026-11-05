package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.domain.token.RefreshToken;
import com.nexus.identity.domain.token.RefreshTokenRepository;
import com.nexus.identity.domain.token.TokenHash;
import com.nexus.identity.domain.user.UserId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Kiểm tra luồng refresh token qua HTTP: cookie, xoay vòng, phát hiện dùng lại, logout và Origin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshFlowIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String PASSWORD = "secret-pass-1";
  private static final String COOKIE = "nexus_rt";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RefreshTokenRepository refreshTokens;

  // Kịch bản: đăng nhập đặt cookie refresh token đủ thuộc tính bảo mật, DB chỉ lưu chuỗi băm
  // Input: đăng ký rồi POST login đúng thông tin
  // Output: Set-Cookie nexus_rt HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth, 30 ngày
  @Test
  void loginSetsHttpOnlyStrictCookieScopedToAuthPath() throws Exception {
    HttpResponse<String> response = login(registerUser());

    assertThat(response.statusCode()).isEqualTo(200);
    String header = setCookie(response);
    assertThat(header).startsWith(COOKIE + "=");
    assertThat(header)
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=2592000");
    String raw = cookieValue(response);
    assertThat(raw).hasSizeGreaterThanOrEqualTo(43);
    assertThat(response.body()).doesNotContain(raw);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from identity.refresh_tokens where token_hash = ?",
                Integer.class,
                raw))
        .isZero();
    assertThat(familyOf(raw)).isNotNull();
  }

  // Kịch bản: refresh xoay vòng sang token mới cùng family, token cũ không dùng được nữa
  // Input: login, refresh bằng cookie cũ, rồi refresh lại bằng cookie cũ
  // Output: 200 kèm access token và cookie mới cùng family; token cũ có replaced_by_id; lần sau 401
  @Test
  void refreshRotatesAndOldTokenBecomesUnusable() throws Exception {
    String oldRaw = cookieValue(login(registerUser()));

    HttpResponse<String> refreshed = refresh(oldRaw);

    assertThat(refreshed.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(refreshed.body());
    assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.get("expiresIn").asInt()).isEqualTo(900);
    assertThat(body.get("accessToken").asText().split("\\.")).hasSize(3);
    String newRaw = cookieValue(refreshed);
    assertThat(setCookie(refreshed)).contains("HttpOnly", "Secure", "SameSite=Strict");
    assertThat(newRaw).isNotEqualTo(oldRaw);
    assertThat(familyOf(newRaw)).isEqualTo(familyOf(oldRaw));
    Map<String, Object> oldRow = row(oldRaw);
    assertThat(oldRow.get("revoked_at")).isNotNull();
    assertThat(oldRow.get("replaced_by_id")).isEqualTo(row(newRaw).get("id"));
    assertThat(row(newRaw).get("revoked_at")).isNull();
    assertThat(refresh(oldRaw).statusCode()).isEqualTo(401);
  }

  // Kịch bản: token cũ bị dùng lại thì cả family bị thu hồi dù request trả lỗi (REQUIRES_NEW)
  // Input: login, refresh lấy token mới, dùng lại token cũ, rồi refresh bằng token mới
  // Output: 401 REFRESH_TOKEN_REUSED kèm xóa cookie; token mới cũng 401; cả family bị revoke
  @Test
  void reusingOldTokenReturns401ReusedAndRevokesWholeFamily() throws Exception {
    String oldRaw = cookieValue(login(registerUser()));
    String newRaw = cookieValue(refresh(oldRaw));

    HttpResponse<String> reused = refresh(oldRaw);

    assertThat(reused.statusCode()).isEqualTo(401);
    assertThat(code(reused)).isEqualTo("REFRESH_TOKEN_REUSED");
    assertDeleteCookie(reused);
    assertWholeFamilyRevoked(familyOf(oldRaw), 2);
    HttpResponse<String> next = refresh(newRaw);
    assertThat(next.statusCode()).isEqualTo(401);
    assertThat(code(next)).isEqualTo("REFRESH_TOKEN_REUSED");
  }

  // Kịch bản: thiếu cookie, cookie rác, token lạ, token hết hạn đều trả cùng một lỗi chung
  // Input: không cookie; chuỗi rác; token 256-bit chưa từng phát; token lưu với hạn đã qua
  // Output: cả bốn 401 UNAUTHENTICATED, body giống hệt nhau trừ requestId, kèm xóa cookie
  @Test
  void refreshWithoutCookieOrGarbageCookieReturns401Unauthenticated() throws Exception {
    String expired = randomRaw();
    UserId user = userIdOf(registerUser());
    Instant past = Instant.now().minus(Duration.ofDays(31)).truncatedTo(ChronoUnit.MICROS);
    refreshTokens.save(
        RefreshToken.issue(
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            TokenHash.ofRaw(expired),
            past,
            Duration.ofDays(30),
            "ua",
            "ip"));

    List<HttpResponse<String>> responses =
        List.of(refresh(null), refresh("garbage!!"), refresh(randomRaw()), refresh(expired));

    JsonNode expected = stripRequestId(responses.get(0).body());
    assertThat(expected.get("code").asText()).isEqualTo("UNAUTHENTICATED");
    for (HttpResponse<String> response : responses) {
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(stripRequestId(response.body())).isEqualTo(expected);
      assertDeleteCookie(response);
    }
    assertThat(row(expired).get("revoked_at")).isNull();
  }

  // Kịch bản: token vừa thu hồi vừa hết hạn bị xuất trình vẫn tính là dùng lại
  // Input: family gồm token hết hạn đã thu hồi và token còn hiệu lực; refresh bằng token hết hạn
  // Output: 401 REFRESH_TOKEN_REUSED và token còn hiệu lực của family cũng bị thu hồi
  @Test
  void reuseOfRevokedAndExpiredTokenIsStillReuse() throws Exception {
    String tag = registerUser();
    String activeRaw = cookieValue(login(tag));
    UUID family = familyOf(activeRaw);
    String expiredRaw = randomRaw();
    Instant past = Instant.now().minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.MICROS);
    RefreshToken expired =
        refreshTokens.save(
            RefreshToken.issue(
                UUID.randomUUID(),
                userIdOf(tag),
                family,
                TokenHash.ofRaw(expiredRaw),
                past,
                Duration.ofDays(30),
                "ua",
                "ip"));
    jdbc.update(
        "update identity.refresh_tokens set revoked_at = ? where id = ?",
        Timestamp.from(past.plus(Duration.ofDays(1))),
        expired.id());

    HttpResponse<String> response = refresh(expiredRaw);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(code(response)).isEqualTo("REFRESH_TOKEN_REUSED");
    assertWholeFamilyRevoked(family, 2);
  }

  // Kịch bản: logout thu hồi token hiện tại, gọi lại hay không có cookie vẫn 204
  // Input: login, logout hai lần với cùng cookie, logout không cookie, rồi refresh bằng cookie đó
  // Output: ba lần 204 kèm xóa cookie; token có revoked_at; refresh sau đó 401
  @Test
  void logoutRevokesTokenAndIsIdempotent() throws Exception {
    String raw = cookieValue(login(registerUser()));

    HttpResponse<String> first = logout(raw);
    HttpResponse<String> second = logout(raw);
    HttpResponse<String> anonymous = logout(null);

    for (HttpResponse<String> response : List.of(first, second, anonymous)) {
      assertThat(response.statusCode()).isEqualTo(204);
      assertDeleteCookie(response);
    }
    assertThat(row(raw).get("revoked_at")).isNotNull();
    assertThat(refresh(raw).statusCode()).isEqualTo(401);
  }

  // Kịch bản: refresh từ Origin không nằm trong danh sách cho phép bị chặn
  // Input: cookie hợp lệ kèm Origin https://evil.example
  // Output: 403 PERMISSION_DENIED, token vẫn chưa bị thu hồi
  @Test
  void refreshWithForeignOriginReturns403() throws Exception {
    String raw = cookieValue(login(registerUser()));

    HttpResponse<String> response = send("/api/v1/auth/refresh", raw, "https://evil.example", null);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("PERMISSION_DENIED");
    assertThat(row(raw).get("revoked_at")).isNull();
  }

  // Kịch bản: refresh từ Origin nằm trong danh sách cho phép thành công
  // Input: cookie hợp lệ kèm Origin http://localhost:3000
  // Output: 200 kèm cookie mới
  @Test
  void refreshWithAllowedOriginSucceeds() throws Exception {
    String raw = cookieValue(login(registerUser()));

    HttpResponse<String> response =
        send("/api/v1/auth/refresh", raw, "http://localhost:3000", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(cookieValue(response)).isNotEqualTo(raw);
  }

  // Kịch bản: Bearer rác trên refresh bị bỏ qua, cookie hợp lệ vẫn được xoay vòng
  // Input: cookie hợp lệ kèm Authorization: Bearer garbage
  // Output: 200 có accessToken
  @Test
  void refreshWorksWithStaleBearerHeader() throws Exception {
    String raw = cookieValue(login(registerUser()));

    HttpResponse<String> response = send("/api/v1/auth/refresh", raw, null, "garbage");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(MAPPER.readTree(response.body()).has("accessToken")).isTrue();
  }

  // Kịch bản: hai request refresh đồng thời với cùng token chỉ một bên thắng
  // Input: hai thread cùng gửi refresh với một cookie sau khi chờ chung một latch
  // Output: đúng một 200 và một 401 REFRESH_TOKEN_REUSED; sau đó mọi token của family bị thu hồi
  @Test
  void concurrentRefreshWithSameTokenOnlyOneWins() throws Exception {
    String raw = cookieValue(login(registerUser()));
    UUID family = familyOf(raw);
    CountDownLatch start = new CountDownLatch(1);
    Callable<HttpResponse<String>> task =
        () -> {
          start.await();
          return refresh(raw);
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<HttpResponse<String>> responses = new ArrayList<>();
    try {
      Future<HttpResponse<String>> a = pool.submit(task);
      Future<HttpResponse<String>> b = pool.submit(task);
      start.countDown();
      responses.add(a.get());
      responses.add(b.get());
    } finally {
      pool.shutdownNow();
    }

    assertThat(responses.stream().map(HttpResponse::statusCode))
        .containsExactlyInAnyOrder(200, 401);
    HttpResponse<String> loser =
        responses.stream().filter(r -> r.statusCode() == 401).findFirst().orElseThrow();
    assertThat(code(loser)).isEqualTo("REFRESH_TOKEN_REUSED");
    assertWholeFamilyRevoked(family, 2);
  }

  // Khẳng định family có đúng số dòng cho trước và dòng nào cũng đã thu hồi
  private void assertWholeFamilyRevoked(UUID family, int expectedRows) {
    List<Timestamp> revoked =
        jdbc.queryForList(
            "select revoked_at from identity.refresh_tokens where family_id = ?",
            Timestamp.class,
            family);
    assertThat(revoked).hasSize(expectedRows).doesNotContainNull();
  }

  // Khẳng định response có Set-Cookie xóa nexus_rt trên đúng Path
  private static void assertDeleteCookie(HttpResponse<String> response) {
    assertThat(setCookie(response))
        .startsWith(COOKIE + "=;")
        .contains("Max-Age=0", "Path=/api/v1/auth");
  }

  // Đọc một dòng refresh_tokens theo token thô
  private Map<String, Object> row(String raw) {
    return jdbc.queryForMap(
        "select id, revoked_at, replaced_by_id from identity.refresh_tokens where token_hash = ?",
        TokenHash.ofRaw(raw).value());
  }

  // Đọc family_id của token thô
  private UUID familyOf(String raw) {
    return jdbc.queryForObject(
        "select family_id from identity.refresh_tokens where token_hash = ?",
        UUID.class,
        TokenHash.ofRaw(raw).value());
  }

  // Đọc id user theo tag đã đăng ký
  private UserId userIdOf(String tag) {
    return new UserId(
        jdbc.queryForObject(
            "select id from identity.users where email = ?", UUID.class, tag + "@x.io"));
  }

  // Sinh token thô 256-bit base64url giống định dạng thật
  private static String randomRaw() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // Lấy header Set-Cookie của nexus_rt
  private static String setCookie(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(h -> h.startsWith(COOKIE + "="))
        .findFirst()
        .orElseThrow();
  }

  // Lấy giá trị cookie nexus_rt từ Set-Cookie
  private static String cookieValue(HttpResponse<String> response) {
    String header = setCookie(response);
    return header.substring(COOKIE.length() + 1, header.indexOf(';'));
  }

  // Đọc code của body Problem Details
  private static String code(HttpResponse<String> response) {
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Bỏ requestId khỏi body Problem Details để so sánh
  private static JsonNode stripRequestId(String body) {
    ObjectNode node = (ObjectNode) MAPPER.readTree(body);
    node.remove("requestId");
    return node;
  }

  // Đăng ký user ngẫu nhiên và trả tag dùng làm username và phần đầu email
  private String registerUser() throws Exception {
    String tag = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String json =
        MAPPER.writeValueAsString(
            Map.of("email", tag + "@x.io", "username", tag, "password", PASSWORD));
    assertThat(post("/api/v1/auth/register", json).statusCode()).isEqualTo(201);
    return tag;
  }

  // Đăng nhập bằng tag đã đăng ký
  private HttpResponse<String> login(String tag) throws Exception {
    return post(
        "/api/v1/auth/login",
        MAPPER.writeValueAsString(Map.of("email", tag + "@x.io", "password", PASSWORD)));
  }

  // Gọi refresh với cookie tùy chọn, không Origin, không Bearer
  private HttpResponse<String> refresh(String raw) throws Exception {
    return send("/api/v1/auth/refresh", raw, null, null);
  }

  // Gọi logout với cookie tùy chọn
  private HttpResponse<String> logout(String raw) throws Exception {
    return send("/api/v1/auth/logout", raw, null, null);
  }

  // Gửi POST rỗng kèm cookie, Origin và Bearer nếu khác null, cookie truyền tay vì có Secure
  private HttpResponse<String> send(String path, String raw, String origin, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (raw != null) {
      builder.header("Cookie", COOKIE + "=" + raw);
    }
    if (origin != null) {
      builder.header("Origin", origin);
    }
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Gửi POST JSON không kèm cookie
  private HttpResponse<String> post(String path, String json) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}

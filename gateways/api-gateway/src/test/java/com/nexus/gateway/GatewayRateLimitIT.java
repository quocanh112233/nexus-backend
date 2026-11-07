package com.nexus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra rate limit trên gateway thật với Redis thật; test Redis chết chạy cuối vì dừng container
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayRateLimitIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String ORIGIN = "http://localhost:3000";

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
  private static final RSAKey KEY = newKey();
  private static final HttpServer JWKS = jwksServer();
  private static final GatewayIT.Stub IDENTITY = new GatewayIT.Stub("identity");
  private static final GatewayIT.Stub COMMUNITY = new GatewayIT.Stub("community");
  private static final GatewayIT.Stub MESSAGING = new GatewayIT.Stub("messaging");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  // Bật rate limit, trỏ Redis vào container, trỏ route và JWKS vào service giả
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    REDIS.start();
    registry.add("app.ratelimit.enabled", () -> "true");
    registry.add("REDIS_HOST", REDIS::getHost);
    registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
    registry.add("IDENTITY_URI", IDENTITY::uri);
    registry.add("COMMUNITY_URI", COMMUNITY::uri);
    registry.add("MESSAGING_URI", MESSAGING::uri);
    registry.add("JWKS_URI", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    registry.add("CORS_ALLOWED_ORIGINS", () -> ORIGIN);
    registry.add("management.server.port", () -> "0");
  }

  // Dừng Redis và các máy chủ giả sau khi chạy hết test
  @AfterAll
  static void stopServers() {
    REDIS.stop();
    IDENTITY.stop();
    COMMUNITY.stop();
    MESSAGING.stop();
    JWKS.stop(0);
  }

  // Xóa mọi bucket để mỗi test bắt đầu từ burst đầy
  @BeforeEach
  void flushRedis() throws Exception {
    if (REDIS.isRunning()) {
      REDIS.execInContainer("redis-cli", "flushall");
    }
  }

  // Kịch bản: auth burst 10, request thứ 11 bị chặn dù giả X-Forwarded-For khác nhau
  // Input: 15 POST /api/v1/auth/login liên tiếp, mỗi lần một X-Forwarded-For khác
  // Output: 10 đầu 200; từ thứ 11 có 429 problem+json RATE_LIMITED, Retry-After >= 1, có requestId
  @Test
  void first10AuthRequestsPassAnd11thGets429WithRetryAfterAndProblemBody() throws Exception {
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      responses.add(
          send("POST", "/api/v1/auth/login", null, Map.of("X-Forwarded-For", "10.0.0." + i)));
    }

    assertThat(responses.subList(0, 10)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    HttpResponse<String> limited = firstLimited(responses, 10);
    assertThat(limited.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()))
        .isGreaterThanOrEqualTo(1);
    JsonNode body = json(limited);
    assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("status").asInt()).isEqualTo(429);
    assertThat(body.get("requestId").asText())
        .isEqualTo(limited.headers().firstValue("X-Request-Id").orElseThrow());
  }

  // Kịch bản: hạn mức API tính theo sub nên user vượt mức không ảnh hưởng user khác
  // Input: user A gọi GET /api/v1/servers 130 lần liên tiếp rồi user B gọi 1 lần
  // Output: 100 lần đầu của A là 200, sau đó A bị 429 kèm Retry-After; B vẫn 200
  @Test
  void apiLimitIsPerSubject() throws Exception {
    String userA = token(UUID.randomUUID().toString());
    List<Integer> statuses = new ArrayList<>();
    HttpResponse<String> lastLimited = null;
    for (int i = 0; i < 130; i++) {
      HttpResponse<String> response = send("GET", "/api/v1/servers", userA, Map.of());
      statuses.add(response.statusCode());
      if (response.statusCode() == 429) {
        lastLimited = response;
      }
    }

    assertThat(statuses.subList(0, 100)).containsOnly(200);
    assertThat(statuses.subList(100, 130)).contains(429);
    assertThat(lastLimited).isNotNull();
    assertThat(json(lastLimited).get("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(lastLimited.headers().firstValue("Retry-After")).isPresent();
    assertThat(
            send("GET", "/api/v1/servers", token(UUID.randomUUID().toString()), Map.of())
                .statusCode())
        .isEqualTo(200);
  }

  // Kịch bản: hạn mức API là tổng theo user trên mọi route có JWT, không tách theo route
  // Input: một user gọi luân phiên users/me, servers, channels/c1/messages (GET), 130 lần
  // Output: 100 lần đầu 200, 30 lần sau có ít nhất một 429
  @Test
  void apiLimitIsSharedAcrossRoutes() throws Exception {
    String user = token(UUID.randomUUID().toString());
    List<Integer> statuses = new ArrayList<>();
    List<String> paths =
        List.of("/api/v1/users/me", "/api/v1/servers", "/api/v1/channels/c1/messages");
    for (int i = 0; i < 130; i++) {
      String path = paths.get(i % 3);
      statuses.add(send("GET", path, user, Map.of()).statusCode());
    }

    assertThat(statuses.subList(0, 100)).containsOnly(200);
    assertThat(statuses.subList(100, 130)).contains(429);
  }

  // Kịch bản: gửi tin có bucket riêng burst 5 nên request thứ 6 trong burst bị chặn
  // Input: 8 POST /api/v1/channels/c1/messages liên tiếp cùng sub, rồi 1 GET cùng sub
  // Output: 5 đầu 200; sau đó có 429 RATE_LIMITED kèm Retry-After; GET đọc tin vẫn 200
  @Test
  void sendMessageRouteLimitsSixthRequestInBurst() throws Exception {
    String user = token(UUID.randomUUID().toString());
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      responses.add(send("POST", "/api/v1/channels/c1/messages", user, Map.of()));
    }

    assertThat(responses.subList(0, 5)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    HttpResponse<String> limited = firstLimited(responses, 5);
    assertThat(json(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    assertThat(send("GET", "/api/v1/channels/c1/messages", user, Map.of()).statusCode())
        .isEqualTo(200);
  }

  // Kịch bản: xem trước invite công khai bị chặn theo IP kết nối để không dò được mã, bucket riêng
  // Input: 60 GET /api/v1/invites/AbCd1234 không token, X-Forwarded-For khác nhau, rồi 1 login
  // Output: 30 đầu 200; từ thứ 31 có 429 RATE_LIMITED; login vẫn 200 vì auth có bucket khác
  @Test
  void invitePreviewIsLimitedPerIp() throws Exception {
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      responses.add(
          send("GET", "/api/v1/invites/AbCd1234", null, Map.of("X-Forwarded-For", "10.1.0." + i)));
    }

    assertThat(responses.subList(0, 30)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    assertThat(json(firstLimited(responses, 30)).get("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(send("POST", "/api/v1/auth/login", null, Map.of()).statusCode()).isEqualTo(200);
  }

  // Kịch bản: preflight và tài liệu không tiêu token của bucket nào
  // Input: 15 preflight /api/v1/auth/login, 15 GET /docs/identity/v3/api-docs, rồi 10 login
  // Output: tất cả preflight và docs 200; cả 10 login vẫn 200 vì bucket auth còn đầy
  @Test
  void optionsAndDocsAreNotLimited() throws Exception {
    Map<String, String> preflight =
        Map.of("Origin", ORIGIN, "Access-Control-Request-Method", "POST");
    for (int i = 0; i < 15; i++) {
      assertThat(send("OPTIONS", "/api/v1/auth/login", null, preflight).statusCode())
          .isEqualTo(200);
      assertThat(send("GET", "/docs/identity/v3/api-docs", null, Map.of()).statusCode())
          .isEqualTo(200);
    }

    for (int i = 0; i < 10; i++) {
      assertThat(send("POST", "/api/v1/auth/login", null, Map.of()).statusCode()).isEqualTo(200);
    }
  }

  // Kịch bản: SPA khác origin vẫn đọc được 429 và Retry-After nhờ header CORS
  // Input: 15 POST /api/v1/auth/login có Origin cho phép, từ thứ 11 bị chặn
  // Output: 429 có đúng một Allow-Origin, Allow-Credentials true, Expose-Headers chứa Retry-After
  @Test
  void rateLimitedResponseCarriesCorsHeaders() throws Exception {
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      responses.add(send("POST", "/api/v1/auth/login", null, Map.of("Origin", ORIGIN)));
    }
    HttpResponse<String> last = firstLimited(responses, 10);

    assertThat(last.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
    assertThat(last.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(last.headers().allValues("Access-Control-Expose-Headers")).hasSize(1);
    assertThat(last.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
        .contains("Retry-After");
  }

  // Kịch bản: token hợp lệ nhưng thiếu sub thì không có khóa đếm, phải bị từ chối chứ không vô hạn
  // Input: GET /api/v1/servers với JWT hợp lệ thiếu claim sub
  // Output: 403, không tới community
  @Test
  void tokenWithoutSubjectIsDeniedNotUnlimited() throws Exception {
    COMMUNITY.reset();

    HttpResponse<String> response = send("GET", "/api/v1/servers", token(null), Map.of());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(COMMUNITY.last()).isNull();
  }

  // Kịch bản: khóa IP chỉ lấy từ kết nối, không có địa chỉ kết nối thì không có khóa
  // Input: request có X-Forwarded-For nhưng có hoặc không có remote address
  // Output: có địa chỉ thì khóa là địa chỉ đó; không có thì Mono rỗng (bị từ chối)
  @Test
  void ipKeyResolverUsesConnectionAddressOnly() {
    var resolver = new RateLimitConfig().ipKeyResolver();
    var withAddress =
        MockServerHttpRequest.get("/x")
            .remoteAddress(new InetSocketAddress("127.0.0.7", 5000))
            .header("X-Forwarded-For", "1.2.3.4");
    var withoutAddress = MockServerHttpRequest.get("/x").header("X-Forwarded-For", "1.2.3.4");

    assertThat(resolver.resolve(MockServerWebExchange.from(withAddress)).block())
        .isEqualTo("127.0.0.7");
    assertThat(resolver.resolve(MockServerWebExchange.from(withoutAddress)).block()).isNull();
  }

  // Kịch bản: Redis chết thì gateway cho qua mọi request và chỉ ghi WARN một lần
  // Input: dừng container Redis rồi gửi 15 POST /api/v1/auth/login (vượt burst 10)
  // Output: cả 15 request 200; log có đúng một dòng cảnh báo Redis không khả dụng
  @Test
  @Order(Integer.MAX_VALUE)
  void redisDownFailsOpen(CapturedOutput output) throws Exception {
    REDIS.stop();

    for (int i = 0; i < 15; i++) {
      assertThat(send("POST", "/api/v1/auth/login", null, Map.of()).statusCode()).isEqualTo(200);
    }

    assertThat(output.getAll().split("failing open", -1).length - 1).isEqualTo(1);
  }

  // Lấy response 429 đầu tiên từ vị trí cho trước, dư request để refill chậm không làm test flaky
  private static HttpResponse<String> firstLimited(List<HttpResponse<String>> responses, int from) {
    return responses.subList(from, responses.size()).stream()
        .filter(r -> r.statusCode() == 429)
        .findFirst()
        .orElseThrow(() -> new AssertionError("không có response 429 sau request " + from));
  }

  // Gửi request tới gateway với method, token Bearer và header tùy chọn
  private HttpResponse<String> send(
      String method, String path, String token, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    headers.forEach(builder::header);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Đọc body JSON của response
  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }

  // Token RS256 hợp lệ ký bằng khóa của test, subject null thì bỏ claim sub
  private static String token(String subject) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer("nexus-identity")
            .audience("nexus")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))));
    if (subject != null) {
      claims.subject(subject);
    }
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
              claims.build());
      jwt.sign(new RSASSASigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Sinh cặp khóa RSA riêng cho test này
  private static RSAKey newKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("rl-1").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Máy chủ JWKS giả phục vụ khóa công khai của test tại /jwks
  private static HttpServer jwksServer() {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

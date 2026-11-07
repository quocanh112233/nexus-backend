package com.nexus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.testsupport.TestJwt;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra gateway thật trên cổng ngẫu nhiên: route, JWT, CORS, request id, lỗi và tài liệu API
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String ORIGIN = "http://localhost:3000";
  private static final String UUID_REGEX =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final Stub IDENTITY = new Stub("identity");
  private static final Stub COMMUNITY = new Stub("community");
  private static final Stub MESSAGING = new Stub("messaging");
  private static final int CLOSED_PORT = closedPort();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  // Trỏ route và JWKS vào service giả, tắt rate limit, timeout response ngắn để thử service treo
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("IDENTITY_URI", IDENTITY::uri);
    registry.add("COMMUNITY_URI", COMMUNITY::uri);
    registry.add("MESSAGING_URI", MESSAGING::uri);
    registry.add("JWKS_URI", JWKS::uri);
    registry.add("CORS_ALLOWED_ORIGINS", () -> ORIGIN + ",http://localhost:5173");
    registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "2s");
    registry.add("management.server.port", () -> "0");
    registry.add("app.ratelimit.enabled", () -> "false");
  }

  // Dừng các máy chủ giả sau khi chạy hết test
  @AfterAll
  static void stopServers() {
    IDENTITY.stop();
    COMMUNITY.stop();
    MESSAGING.stop();
    JWKS.close();
  }

  // Kịch bản: đường /api/v1/auth/** công khai và tới identity
  // Input: POST /api/v1/auth/login không có Authorization
  // Output: 200 từ identity giả, đường dẫn giữ nguyên
  @Test
  void authPrefixIsForwardedToIdentityWithoutJwt() throws Exception {
    HttpResponse<String> response = send("POST", "/api/v1/auth/login", null, Map.of());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("service").asText()).isEqualTo("identity");
    assertThat(IDENTITY.last.get().path()).isEqualTo("/api/v1/auth/login");
  }

  // Kịch bản: Bearer cũ hay rác trên đường công khai bị bỏ qua nhưng vẫn chuyển xuống service
  // Input: login, refresh, xem trước invite kèm token hết hạn và token rác; accept kèm token rác
  // Output: các đường công khai 200 và giữ header Authorization; accept 401 UNAUTHENTICATED
  @Test
  void staleOrGarbageBearerIsIgnoredOnPublicPaths() throws Exception {
    String expired =
        JWT.token(UUID.randomUUID(), "alice", Instant.now().minus(Duration.ofHours(1)));
    for (String token : new String[] {expired, "garbage"}) {
      assertThat(send("POST", "/api/v1/auth/login", token, Map.of()).statusCode()).isEqualTo(200);
      assertThat(IDENTITY.last.get().authorization()).isEqualTo("Bearer " + token);
      assertThat(send("POST", "/api/v1/auth/refresh", token, Map.of()).statusCode()).isEqualTo(200);
      assertThat(send("GET", "/api/v1/invites/AbCd1234", token, Map.of()).statusCode())
          .isEqualTo(200);
      assertThat(COMMUNITY.last.get().authorization()).isEqualTo("Bearer " + token);
    }

    HttpResponse<String> accept =
        send("POST", "/api/v1/invites/AbCd1234/accept", "garbage", Map.of());

    assertThat(accept.statusCode()).isEqualTo(401);
    assertThat(json(accept).get("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  // Kịch bản: route cần JWT mà không gửi token
  // Input: GET /api/v1/servers không có Authorization
  // Output: 401 application/problem+json code UNAUTHENTICATED, requestId trùng header
  @Test
  void protectedRouteWithoutTokenReturns401Problem() throws Exception {
    HttpResponse<String> response = send("GET", "/api/v1/servers", null, Map.of());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    JsonNode body = json(response);
    assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(body.get("status").asInt()).isEqualTo(401);
    assertThat(body.get("requestId").asText())
        .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
  }

  // Kịch bản: token hợp lệ qua được còn mọi token giả bị chặn tại gateway
  // Input: token hợp lệ; none, khóa khác, hết hạn, sai aud, sai iss, HS256 khóa công khai, RS512
  // Output: hợp lệ 200; cả bảy token giả 401 UNAUTHENTICATED
  @Test
  void forgedTokensGet401() throws Exception {
    UUID user = UUID.randomUUID();
    assertThat(send("GET", "/api/v1/servers", JWT.token(user, "alice"), Map.of()).statusCode())
        .isEqualTo(200);
    Map<String, String> forged = new LinkedHashMap<>();
    forged.put("alg=none", new PlainJWT(claims(user)).serialize());
    forged.put("other key", TestJwt.create().token(user, "mallory"));
    forged.put("expired", JWT.token(user, "mallory", Instant.now().minus(Duration.ofHours(1))));
    forged.put("wrong aud", JWT.tokenWithAudience(user, "mallory", "other"));
    forged.put("wrong iss", JWT.tokenWithIssuer(user, "mallory", "evil-issuer"));
    forged.put("HS256 with RSA public key", hs256WithPublicKey(claims(user)));
    forged.put("RS512 same key", JWT.tokenWithAlgorithm(user, "mallory", JWSAlgorithm.RS512));

    for (Map.Entry<String, String> entry : forged.entrySet()) {
      HttpResponse<String> response = send("GET", "/api/v1/servers", entry.getValue(), Map.of());
      assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(401);
      assertThat(json(response).get("code").asText())
          .as(entry.getKey())
          .isEqualTo("UNAUTHENTICATED");
    }
  }

  // Kịch bản: token hợp lệ được chuyển tiếp kèm Authorization và X-Request-Id
  // Input: GET /api/v1/users/me với token hợp lệ, service giả trả lại X-Request-Id
  // Output: 200; service nhận Bearer và request id; response chỉ có một X-Request-Id trùng giá trị
  @Test
  void validTokenIsForwardedWithAuthorizationAndRequestId() throws Exception {
    String token = JWT.token(UUID.randomUUID(), "alice");

    HttpResponse<String> response = send("GET", "/api/v1/users/me", token, Map.of());

    assertThat(response.statusCode()).isEqualTo(200);
    Seen seen = IDENTITY.last.get();
    assertThat(seen.authorization()).isEqualTo("Bearer " + token);
    assertThat(response.headers().allValues("X-Request-Id")).containsExactly(seen.requestId());
    assertThat(seen.requestId()).matches(UUID_REGEX);
  }

  // Kịch bản: X-Request-Id an toàn từ client được giữ nguyên
  // Input: GET /api/v1/servers với X-Request-Id "abc.DEF_123-x"
  // Output: service và response đều thấy đúng "abc.DEF_123-x"
  @Test
  void incomingSafeRequestIdIsPreserved() throws Exception {
    HttpResponse<String> response =
        send(
            "GET",
            "/api/v1/servers",
            JWT.token(UUID.randomUUID(), "alice"),
            Map.of("X-Request-Id", "abc.DEF_123-x"));

    assertThat(response.headers().allValues("X-Request-Id")).containsExactly("abc.DEF_123-x");
    assertThat(COMMUNITY.last.get().requestId()).isEqualTo("abc.DEF_123-x");
  }

  // Kịch bản: X-Request-Id rác hoặc quá dài bị thay bằng UUID
  // Input: X-Request-Id "evil<script>" rồi chuỗi 65 ký tự
  // Output: response và service nhận UUID mới thay cho giá trị rác
  @Test
  void garbageRequestIdIsReplaced() throws Exception {
    String token = JWT.token(UUID.randomUUID(), "alice");
    for (String garbage : new String[] {"evil<script>", "a".repeat(65)}) {
      HttpResponse<String> response =
          send("GET", "/api/v1/servers", token, Map.of("X-Request-Id", garbage));

      String id = response.headers().firstValue("X-Request-Id").orElseThrow();
      assertThat(id).matches(UUID_REGEX);
      assertThat(COMMUNITY.last.get().requestId()).isEqualTo(id);
    }
  }

  // Kịch bản: xem trước invite công khai, còn accept cần JWT
  // Input: GET /api/v1/invites/{code} không token; POST accept không token rồi có token hợp lệ
  // Output: xem trước 200; accept không token 401; accept có token 200 tới community
  @Test
  void invitePreviewIsPublicButAcceptRequiresJwt() throws Exception {
    assertThat(send("GET", "/api/v1/invites/AbCd1234", null, Map.of()).statusCode()).isEqualTo(200);
    assertThat(send("POST", "/api/v1/invites/AbCd1234/accept", null, Map.of()).statusCode())
        .isEqualTo(401);

    HttpResponse<String> accepted =
        send(
            "POST",
            "/api/v1/invites/AbCd1234/accept",
            JWT.token(UUID.randomUUID(), "bob"),
            Map.of());

    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(COMMUNITY.last.get().path()).isEqualTo("/api/v1/invites/AbCd1234/accept");
  }

  // Kịch bản: preflight chỉ mở cho origin đã cấu hình và không cần JWT
  // Input: OPTIONS /api/v1/servers với Origin cho phép rồi Origin lạ
  // Output: origin cho phép 200 kèm Allow-Origin; origin lạ 403 không có Allow-Origin
  @Test
  void corsPreflightAllowsConfiguredOriginOnly() throws Exception {
    HttpResponse<String> allowed = preflight("/api/v1/servers", ORIGIN);

    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElseThrow())
        .contains("PATCH");

    HttpResponse<String> evil = preflight("/api/v1/servers", "https://evil.example");

    assertThat(evil.statusCode()).isEqualTo(403);
    assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  // Kịch bản: credentials CORS chỉ bật cho đường xác thực
  // Input: preflight /api/v1/auth/refresh và /api/v1/servers; POST /api/v1/auth/login có Origin
  // Output: auth có Allow-Credentials true, expose X-Request-Id; servers không có Allow-Credentials
  @Test
  void credentialsAllowedOnlyOnAuthPaths() throws Exception {
    assertThat(
            preflight("/api/v1/auth/refresh", ORIGIN)
                .headers()
                .firstValue("Access-Control-Allow-Credentials"))
        .hasValue("true");
    assertThat(
            preflight("/api/v1/servers", ORIGIN)
                .headers()
                .firstValue("Access-Control-Allow-Credentials"))
        .isEmpty();

    HttpResponse<String> login = send("POST", "/api/v1/auth/login", null, Map.of("Origin", ORIGIN));

    assertThat(login.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
    assertThat(login.headers().allValues("Access-Control-Allow-Credentials"))
        .containsExactly("true");
    assertThat(login.headers().allValues("Access-Control-Expose-Headers")).hasSize(1);
    assertThat(login.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
        .contains("X-Request-Id");
  }

  // Kịch bản: SPA khác origin vẫn đọc được 401 và 404 nhờ header CORS trên response lỗi
  // Input: GET /api/v1/servers không token và GET /api/v1/nope có token, Origin cho phép rồi lạ
  // Output: 401/404 có đúng một Allow-Origin; origin lạ bị 403 và không có Allow-Origin
  @Test
  void errorResponsesCarryCorsHeaders() throws Exception {
    Map<String, String> origin = Map.of("Origin", ORIGIN);
    HttpResponse<String> unauthenticated = send("GET", "/api/v1/servers", null, origin);
    HttpResponse<String> notFound =
        send("GET", "/api/v1/nope", JWT.token(UUID.randomUUID(), "alice"), origin);

    assertThat(unauthenticated.statusCode()).isEqualTo(401);
    assertThat(unauthenticated.headers().allValues("Access-Control-Allow-Origin"))
        .containsExactly(ORIGIN);
    assertThat(unauthenticated.headers().allValues("Access-Control-Expose-Headers")).hasSize(1);
    assertThat(unauthenticated.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    assertThat(notFound.statusCode()).isEqualTo(404);
    assertThat(notFound.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);

    HttpResponse<String> evil =
        send("GET", "/api/v1/servers", null, Map.of("Origin", "https://evil.example"));

    assertThat(evil.statusCode()).isEqualTo(403);
    assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
  }

  // Kịch bản: response 200 đi qua route khác origin không bị lặp header CORS
  // Input: GET /api/v1/servers với token hợp lệ và Origin cho phép
  // Output: 200 với đúng một Allow-Origin và một Expose-Headers
  @Test
  void routedResponseHasSingleCorsHeaders() throws Exception {
    HttpResponse<String> response =
        send(
            "GET",
            "/api/v1/servers",
            JWT.token(UUID.randomUUID(), "alice"),
            Map.of("Origin", ORIGIN));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
    assertThat(response.headers().allValues("Access-Control-Expose-Headers")).hasSize(1);
  }

  // Kịch bản: service phía sau không kết nối được
  // Input: GET route test trỏ tới cổng đóng, kèm token hợp lệ
  // Output: 503 Problem Details code DEPENDENCY_UNAVAILABLE có requestId
  @Test
  void downstreamDownReturns503Problem() throws Exception {
    HttpResponse<String> response =
        send("GET", "/api/v1/down/x", JWT.token(UUID.randomUUID(), "alice"), Map.of());

    assertThat(response.statusCode()).isEqualTo(503);
    JsonNode body = json(response);
    assertThat(body.get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(body.get("requestId").asText())
        .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
  }

  // Kịch bản: service phía sau treo quá response-timeout
  // Input: GET /api/v1/users/slow (service giả ngủ 5 giây), response-timeout test 2 giây
  // Output: 503 DEPENDENCY_UNAVAILABLE trước khi service giả trả lời
  @Test
  void hungDownstreamTimesOutWith503() throws Exception {
    long start = System.nanoTime();

    HttpResponse<String> response =
        send("GET", "/api/v1/users/slow", JWT.token(UUID.randomUUID(), "alice"), Map.of());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(json(response).get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }

  // Kịch bản: đường không khớp route nào
  // Input: GET /api/v1/nope kèm token hợp lệ
  // Output: 404 Problem Details code NOT_FOUND
  @Test
  void unknownPathReturns404Problem() throws Exception {
    HttpResponse<String> response =
        send("GET", "/api/v1/nope", JWT.token(UUID.randomUUID(), "alice"), Map.of());

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(json(response).get("code").asText()).isEqualTo("NOT_FOUND");
  }

  // Kịch bản: tài liệu OpenAPI của từng service đi qua gateway không cần JWT
  // Input: GET /docs/{identity,community,messaging}/v3/api-docs, swagger-config
  // Output: 200 từ đúng service với đường /v3/api-docs; swagger-config liệt kê ba url
  @Test
  void docsRoutesAreProxiedAndPublic() throws Exception {
    HttpResponse<String> identity = send("GET", "/docs/identity/v3/api-docs", null, Map.of());
    HttpResponse<String> community = send("GET", "/docs/community/v3/api-docs", null, Map.of());

    assertThat(identity.statusCode()).isEqualTo(200);
    assertThat(json(identity).get("service").asText()).isEqualTo("identity");
    assertThat(IDENTITY.last.get().path()).isEqualTo("/v3/api-docs");
    assertThat(community.statusCode()).isEqualTo(200);
    assertThat(json(community).get("service").asText()).isEqualTo("community");
    assertThat(COMMUNITY.last.get().path()).isEqualTo("/v3/api-docs");
    HttpResponse<String> messaging = send("GET", "/docs/messaging/v3/api-docs", null, Map.of());
    assertThat(messaging.statusCode()).isEqualTo(200);
    assertThat(json(messaging).get("service").asText()).isEqualTo("messaging");
    assertThat(MESSAGING.last.get().path()).isEqualTo("/v3/api-docs");

    HttpResponse<String> config = send("GET", "/v3/api-docs/swagger-config", null, Map.of());

    assertThat(config.statusCode()).isEqualTo(200);
    assertThat(config.body())
        .contains("/docs/identity/v3/api-docs")
        .contains("/docs/community/v3/api-docs")
        .contains("/docs/messaging/v3/api-docs");
    assertThat(send("GET", "/swagger-ui.html", null, Map.of()).statusCode()).isLessThan(400);
  }

  // Kịch bản: /api/v1/servers/** tới community, /api/v1/users/** tới identity
  // Input: GET /api/v1/servers/123 và /api/v1/users/me với token hợp lệ
  // Output: body báo đúng service và đường dẫn giữ nguyên
  @Test
  void serversPathRoutesToCommunity() throws Exception {
    String token = JWT.token(UUID.randomUUID(), "alice");

    HttpResponse<String> servers = send("GET", "/api/v1/servers/123", token, Map.of());

    assertThat(json(servers).get("service").asText()).isEqualTo("community");
    assertThat(COMMUNITY.last.get().path()).isEqualTo("/api/v1/servers/123");
    assertThat(json(send("GET", "/api/v1/users/me", token, Map.of())).get("service").asText())
        .isEqualTo("identity");
  }

  // Kịch bản: /api/v1/channels/** tới messaging và giữ Bearer
  // Input: GET /api/v1/channels/c1/messages với token hợp lệ
  // Output: 200 từ messaging giả, đường dẫn giữ nguyên, nhận đúng Bearer
  @Test
  void channelsRouteForwardsToMessagingWithBearer() throws Exception {
    String token = JWT.token(UUID.randomUUID(), "alice");

    HttpResponse<String> response = send("GET", "/api/v1/channels/c1/messages", token, Map.of());

    assertThat(json(response).get("service").asText()).isEqualTo("messaging");
    assertThat(MESSAGING.last().path()).isEqualTo("/api/v1/channels/c1/messages");
    assertThat(MESSAGING.last().authorization()).isEqualTo("Bearer " + token);
  }

  // Kịch bản: /api/v1/attachments/** tới messaging
  // Input: POST /api/v1/attachments với token hợp lệ
  // Output: 200 từ messaging giả, đường dẫn giữ nguyên
  @Test
  void attachmentsRouteForwardsToMessaging() throws Exception {
    String token = JWT.token(UUID.randomUUID(), "alice");

    HttpResponse<String> response = send("POST", "/api/v1/attachments", token, Map.of());

    assertThat(json(response).get("service").asText()).isEqualTo("messaging");
    assertThat(MESSAGING.last().path()).isEqualTo("/api/v1/attachments");
  }

  // Kịch bản: route messaging không công khai
  // Input: GET /api/v1/channels/c1/messages và POST /api/v1/attachments không Authorization
  // Output: cả hai 401 UNAUTHENTICATED và messaging giả không nhận request nào
  @Test
  void messagingRouteRequiresJwt() throws Exception {
    MESSAGING.reset();

    HttpResponse<String> channels = send("GET", "/api/v1/channels/c1/messages", null, Map.of());
    HttpResponse<String> attachments = send("POST", "/api/v1/attachments", null, Map.of());

    assertThat(channels.statusCode()).isEqualTo(401);
    assertThat(attachments.statusCode()).isEqualTo(401);
    assertThat(json(channels).get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(MESSAGING.last()).isNull();
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

  // Gửi preflight CORS cho POST kèm header Authorization
  private HttpResponse<String> preflight(String path, String origin) throws Exception {
    return send(
        "OPTIONS",
        path,
        null,
        Map.of(
            "Origin",
            origin,
            "Access-Control-Request-Method",
            "POST",
            "Access-Control-Request-Headers",
            "Authorization"));
  }

  // Đọc body JSON của response
  private static JsonNode json(HttpResponse<String> response) {
    return MAPPER.readTree(response.body());
  }

  // Dựng claim giống token của Identity cho user
  private static JWTClaimsSet claims(UUID user) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject(user.toString())
        .issuer("nexus-identity")
        .audience("nexus")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
        .build();
  }

  // Tấn công nhầm thuật toán: ký HS256 với bí mật là byte khóa công khai RSA lấy từ JWKS
  private static String hs256WithPublicKey(JWTClaimsSet claims) {
    try {
      RSAKey publicKey = JWKSet.parse(JWT.jwks()).getKeys().get(0).toRSAKey();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(publicKey.getKeyID()).build(),
              claims);
      jwt.sign(new MACSigner(publicKey.toRSAPublicKey().getEncoded()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Lấy một cổng loopback vừa được giải phóng để làm service đã chết
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // Thêm route chỉ dùng trong test trỏ tới cổng đóng
  @TestConfiguration
  static class DownRouteConfig {

    // Route /api/v1/down/** tới service không tồn tại
    @Bean
    RouteLocator downRoute(RouteLocatorBuilder builder) {
      return builder
          .routes()
          .route("down", r -> r.path("/api/v1/down/**").uri("http://127.0.0.1:" + CLOSED_PORT))
          .build();
    }
  }

  // Thông tin request cuối cùng service giả nhận được
  record Seen(String path, String authorization, String requestId) {}

  // Service giả bằng HttpServer của JDK, trả JSON báo tên service và lặp lại X-Request-Id
  static final class Stub {

    private final HttpServer server;
    private final AtomicReference<Seen> last = new AtomicReference<>();

    // Khởi động trên loopback cổng ngẫu nhiên, đường kết thúc bằng /slow ngủ 5 giây
    Stub(String name) {
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            last.set(
                new Seen(path, exchange.getRequestHeaders().getFirst("Authorization"), requestId));
            if (path.endsWith("/slow")) {
              sleep();
            }
            byte[] body =
                ("{\"service\":\"" + name + "\",\"path\":\"" + path + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (requestId != null) {
              exchange.getResponseHeaders().add("X-Request-Id", requestId);
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    }

    // Request cuối cùng service giả nhận được, null nếu chưa có
    Seen last() {
      return last.get();
    }

    // Xóa request cuối để kiểm tra service không nhận gì thêm
    void reset() {
      last.set(null);
    }

    // URI gốc của service giả
    String uri() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // Dừng service giả
    void stop() {
      server.stop(0);
    }

    // Ngủ 5 giây để giả service treo
    private static void sleep() {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

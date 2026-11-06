package com.nexus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.testsupport.TestJwt;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra gateway khi JWKS treo hay lỗi và giới hạn tải lại JWKS với kid lạ; thứ tự test cố định
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayJwksIT {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt ROTATED = TestJwt.create();
  private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
  private static final AtomicInteger FETCHES = new AtomicInteger();
  private static final HttpServer JWKS = jwksServer();
  private static final GatewayIT.Stub COMMUNITY = new GatewayIT.Stub("community");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  // Trỏ JWKS vào máy chủ giả đổi được chế độ, timeout JWKS 1 giây
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("JWKS_URI", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    registry.add("COMMUNITY_URI", COMMUNITY::uri);
    registry.add("IDENTITY_URI", COMMUNITY::uri);
    registry.add("JWKS_RESPONSE_TIMEOUT", () -> "1s");
    registry.add("JWKS_TTL", () -> "4s");
    registry.add("management.server.port", () -> "0");
    registry.add("app.ratelimit.enabled", () -> "false");
  }

  // Dừng các máy chủ giả
  @AfterAll
  static void stopServers() {
    JWKS.stop(0);
    COMMUNITY.stop();
  }

  // Kịch bản: JWKS treo thì request cần JWT thất bại nhanh, không treo theo
  // Input: JWKS ngủ 10 giây, GET /api/v1/servers với token hợp lệ, timeout JWKS 1 giây
  // Output: 503 DEPENDENCY_UNAVAILABLE trong dưới 5 giây
  @Test
  @Order(1)
  void hungJwksFailsFastWith503() throws Exception {
    MODE.set("hung");
    long start = System.nanoTime();

    HttpResponse<String> response = get(JWT.token(UUID.randomUUID(), "alice"));

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(code(response)).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }

  // Kịch bản: JWKS trả lỗi 5xx
  // Input: JWKS trả 500, GET /api/v1/servers với token hợp lệ
  // Output: 503 DEPENDENCY_UNAVAILABLE
  @Test
  @Order(2)
  void failingJwksReturns503() throws Exception {
    MODE.set("error");

    HttpResponse<String> response = get(JWT.token(UUID.randomUUID(), "alice"));

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(code(response)).isEqualTo("DEPENDENCY_UNAVAILABLE");
  }

  // Kịch bản: token với kid ngẫu nhiên không làm gateway gọi JWKS liên tục
  // Input: JWKS hoạt động lại; một token hợp lệ rồi 20 token kid ngẫu nhiên
  // Output: token hợp lệ 200; 20 token kia 401; tổng chỉ một lần tải JWKS thành công thêm
  @Test
  @Order(3)
  void randomKidTokensDoNotRefetchJwks() throws Exception {
    MODE.set("ok");
    int before = FETCHES.get();
    assertThat(get(JWT.token(UUID.randomUUID(), "alice")).statusCode()).isEqualTo(200);
    RSAKey attacker = new RSAKeyGenerator(2048).generate();

    for (int i = 0; i < 20; i++) {
      HttpResponse<String> response = get(randomKidToken(attacker));
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(code(response)).isEqualTo("UNAUTHENTICATED");
    }

    assertThat(FETCHES.get() - before).isEqualTo(1);
  }

  // Kịch bản: Identity thay khóa nhưng giữ nguyên kid, gateway phải bỏ khóa cũ sau khi cache hết
  // ttl
  // Input: token ký khóa cũ rồi khóa mới cùng kid test-1; JWKS đổi sang khóa mới; chờ quá ttl 4
  // giây
  // Output: trước khi đổi khóa cũ 200; sau ttl token khóa mới 200 và token khóa cũ 401
  @Test
  @Order(4)
  void sameKidKeyRotationIsPickedUpAfterTtl() throws Exception {
    MODE.set("ok");
    assertThat(get(JWT.token(UUID.randomUUID(), "alice")).statusCode()).isEqualTo(200);
    MODE.set("rotated");
    Thread.sleep(Duration.ofMillis(4500));

    assertThat(get(ROTATED.token(UUID.randomUUID(), "alice")).statusCode()).isEqualTo(200);
    HttpResponse<String> old = get(JWT.token(UUID.randomUUID(), "alice"));
    assertThat(old.statusCode()).isEqualTo(401);
    assertThat(code(old)).isEqualTo("UNAUTHENTICATED");
  }

  // Ký token đúng iss/aud bằng khóa lạ với kid ngẫu nhiên
  private static String randomKidToken(RSAKey key) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .issuer("nexus-identity")
            .audience("nexus")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(UUID.randomUUID().toString()).build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  // Gọi GET /api/v1/servers kèm Bearer token
  private HttpResponse<String> get(String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers"))
            .header("Authorization", "Bearer " + token)
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // Đọc trường code của body Problem Details
  private static String code(HttpResponse<String> response) {
    return MAPPER.readTree(response.body()).get("code").asText();
  }

  // Máy chủ JWKS giả: ok trả JWKS, rotated trả khóa mới cùng kid, hung ngủ 10 giây, error trả 500
  private static HttpServer jwksServer() {
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    byte[] body = JWT.jwks().getBytes(StandardCharsets.UTF_8);
    byte[] rotated = ROTATED.jwks().getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/jwks",
        exchange -> {
          switch (MODE.get()) {
            case "hung" -> {
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              exchange.sendResponseHeaders(500, -1);
            }
            case "error" -> exchange.sendResponseHeaders(500, -1);
            default -> {
              FETCHES.incrementAndGet();
              byte[] payload = MODE.get().equals("rotated") ? rotated : body;
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, payload.length);
              exchange.getResponseBody().write(payload);
            }
          }
          exchange.close();
        });
    server.start();
    return server;
  }
}

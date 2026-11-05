package com.nexus.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.TestJwtKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Kiểm tra POST /api/v1/auth/login và việc từ chối token giả trên endpoint cần xác thực
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIT extends IdentityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String PASSWORD = "secret-pass-1";

  @LocalServerPort private int port;

  // Kịch bản: đăng nhập đúng email và mật khẩu nhận access token Bearer 15 phút
  // Input: đăng ký user rồi POST login với email viết hoa và đúng mật khẩu
  // Output: 200, tokenType Bearer, expiresIn 900, accessToken là JWT ba phần, không lộ mật khẩu
  @Test
  void loginSuccessReturnsBearerToken() throws Exception {
    String tag = tag();
    register(tag);

    HttpResponse<String> response = login(tag.toUpperCase() + "@x.io", PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.get("expiresIn").asInt()).isEqualTo(900);
    assertThat(body.get("accessToken").asText().split("\\.")).hasSize(3);
    assertThat(response.body()).doesNotContain(PASSWORD);
  }

  // Kịch bản: email không tồn tại, sai mật khẩu, email sai định dạng đều trả cùng một lỗi 401
  // Input: login email lạ; email đúng sai mật khẩu; email sai định dạng; mật khẩu ngắn
  // Output: cả bốn 401 UNAUTHENTICATED, body giống hệt nhau sau khi bỏ requestId và instance
  @Test
  void unknownEmailAndWrongPasswordReturnIdenticalBodies() throws Exception {
    String tag = tag();
    register(tag);

    HttpResponse<String> unknown = login(tag() + "@x.io", PASSWORD);
    HttpResponse<String> wrong = login(tag + "@x.io", "wrong-pass-1");
    HttpResponse<String> malformed = login("not-an-email", PASSWORD);
    HttpResponse<String> shortPassword = login(tag + "@x.io", "abc");

    assertThat(unknown.statusCode()).isEqualTo(401);
    JsonNode expected = stripVolatile(unknown.body());
    assertThat(expected.get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(expected.get("detail").asText()).isEqualTo("Invalid credentials");
    for (HttpResponse<String> other : new HttpResponse[] {wrong, malformed, shortPassword}) {
      assertThat(other.statusCode()).isEqualTo(401);
      assertThat(stripVolatile(other.body())).isEqualTo(expected);
      assertThat(other.headers().firstValue("Content-Type"))
          .isEqualTo(unknown.headers().firstValue("Content-Type"));
    }
  }

  // Kịch bản: token hợp lệ qua được lớp xác thực, mọi token giả bị chặn 401 trên /users/me
  // Input: token từ login; alg=none; ký bằng khóa khác; exp đã qua; aud=other
  // Output: token hợp lệ nhận 200; cả bốn token giả 401 UNAUTHENTICATED
  @Test
  void forgedTokensGet401OnProtectedEndpoint() throws Exception {
    String tag = tag();
    register(tag);
    String valid =
        MAPPER.readTree(login(tag + "@x.io", PASSWORD).body()).get("accessToken").asText();
    Instant now = Instant.now();
    PrivateKey realKey = JWT_KEYS.getPrivate();
    Map<String, String> forged =
        Map.of(
            "alg=none",
            new PlainJWT(claims(now, now.plus(Duration.ofMinutes(15)), "nexus")).serialize(),
            "other key",
            sign(claims(now, now.plus(Duration.ofMinutes(15)), "nexus"), otherKey()),
            "expired",
            sign(
                claims(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), "nexus"),
                realKey),
            "wrong aud",
            sign(claims(now, now.plus(Duration.ofMinutes(15)), "other"), realKey));

    assertThat(getMe(null).statusCode()).isEqualTo(401);
    assertThat(getMe(valid).statusCode()).isEqualTo(200);
    forged.forEach(
        (name, token) -> {
          HttpResponse<String> response = getMe(token);
          assertThat(response.statusCode()).as(name).isEqualTo(401);
          assertThat(MAPPER.readTree(response.body()).get("code").asText())
              .as(name)
              .isEqualTo("UNAUTHENTICATED");
        });
  }

  // Kịch bản: header Bearer rác hoặc đã hết hạn trên login bị bỏ qua, đăng nhập vẫn thành công
  // Input: POST login đúng thông tin kèm Bearer garbage; kèm Bearer ký thật nhưng exp đã qua
  // Output: cả hai 200 có accessToken
  @Test
  void staleBearerOnLoginIsIgnored() throws Exception {
    String tag = tag();
    register(tag);
    Instant now = Instant.now();
    String expired =
        sign(
            claims(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), "nexus"),
            JWT_KEYS.getPrivate());

    for (String bearer : new String[] {"garbage", expired}) {
      HttpResponse<String> response = login(tag + "@x.io", PASSWORD, bearer);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(MAPPER.readTree(response.body()).has("accessToken")).isTrue();
    }
  }

  // Kịch bản: bỏ qua Bearer chỉ áp dụng cho đường dẫn auth, endpoint bảo vệ vẫn chặn token rác
  // Input: GET /api/v1/users/me kèm Bearer garbage
  // Output: 401 UNAUTHENTICATED
  @Test
  void garbageBearerOnProtectedEndpointStill401() throws Exception {
    HttpResponse<String> response = getMe("garbage");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  // Dựng claim giống token thật với thời điểm phát, hạn và audience cho trước
  private static JWTClaimsSet claims(Instant issuedAt, Instant expiresAt, String audience) {
    return new JWTClaimsSet.Builder()
        .subject(UUID.randomUUID().toString())
        .claim("username", "mallory")
        .issuer("nexus-identity")
        .audience(audience)
        .issueTime(Date.from(issuedAt))
        .expirationTime(Date.from(expiresAt))
        .jwtID(UUID.randomUUID().toString())
        .build();
  }

  // Ký claim bằng RS256 với kid nexus-1 bằng khóa riêng cho trước
  private static String sign(JWTClaimsSet claims, PrivateKey key) {
    try {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("nexus-1").build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Sinh khóa riêng RSA không liên quan tới khóa của Identity
  private static PrivateKey otherKey() {
    return TestJwtKeys.generate().getPrivate();
  }

  // Bỏ requestId và instance khỏi body Problem Details để so sánh
  private static JsonNode stripVolatile(String body) {
    ObjectNode node = (ObjectNode) MAPPER.readTree(body);
    node.remove("requestId");
    node.remove("instance");
    return node;
  }

  // Sinh chuỗi ngẫu nhiên hợp lệ cho username và phần đầu email
  private static String tag() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  // Đăng ký user với email tag@x.io, username tag và mật khẩu chung
  private void register(String tag) throws Exception {
    String json =
        MAPPER.writeValueAsString(
            Map.of("email", tag + "@x.io", "username", tag, "password", PASSWORD));
    HttpResponse<String> response = post("/api/v1/auth/register", json);
    assertThat(response.statusCode()).isEqualTo(201);
  }

  // Gọi POST /api/v1/auth/login với email và mật khẩu, không kèm header Authorization
  private HttpResponse<String> login(String email, String password) throws Exception {
    return login(email, password, null);
  }

  // Gọi POST /api/v1/auth/login với email, mật khẩu và Bearer tùy chọn
  private HttpResponse<String> login(String email, String password, String bearer)
      throws Exception {
    return post(
        "/api/v1/auth/login",
        MAPPER.writeValueAsString(Map.of("email", email, "password", password)),
        bearer);
  }

  // Gửi POST JSON tới đường dẫn trên cổng ứng dụng, không kèm header Authorization
  private HttpResponse<String> post(String path, String json) throws Exception {
    return post(path, json, null);
  }

  // Gửi POST JSON tới đường dẫn trên cổng ứng dụng, bearer khác null thì gửi header Authorization
  private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // Gọi GET /api/v1/users/me, token null thì không gửi header Authorization
  private HttpResponse<String> getMe(String token) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me"));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    try {
      return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

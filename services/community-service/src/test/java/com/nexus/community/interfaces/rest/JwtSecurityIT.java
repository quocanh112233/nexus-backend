package com.nexus.community.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.community.CommunityIntegrationTest;
import com.nexus.testsupport.TestJwt;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra resource server chỉ nhận token RS256 đúng khóa JWKS, iss, aud và còn hạn
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtSecurityIT extends CommunityIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  // Kịch bản: token ký bằng khóa trong JWKS được chấp nhận
  // Input: GET /api/v1/servers với token hợp lệ từ TestJwt
  // Output: 200
  @Test
  void tokenFromJwksIsAccepted() throws Exception {
    assertThat(get(JWT.token(UUID.randomUUID(), "alice")).statusCode()).isEqualTo(200);
  }

  // Kịch bản: mọi token giả bị chặn 401 UNAUTHENTICATED
  // Input: alg=none; khóa khác; hết hạn; sai aud; HS256 dùng khóa công khai RSA; RS512 cùng khóa
  // Output: cả sáu 401 với code UNAUTHENTICATED
  @Test
  void forgedTokensGet401() throws Exception {
    UUID user = UUID.randomUUID();
    Instant now = Instant.now();
    Map<String, String> forged =
        Map.of(
            "alg=none",
            new PlainJWT(claims(user, "nexus")).serialize(),
            "other key",
            TestJwt.create().token(user, "mallory"),
            "expired",
            JWT.token(user, "mallory", now.minus(Duration.ofHours(1))),
            "wrong aud",
            JWT.tokenWithAudience(user, "mallory", "other"),
            "HS256 with RSA public key",
            hs256WithPublicKey(claims(user, "nexus")),
            "RS512 same key",
            JWT.tokenWithAlgorithm(user, "mallory", JWSAlgorithm.RS512));

    forged.forEach(
        (name, token) -> {
          HttpResponse<String> response = get(token);
          assertThat(response.statusCode()).as(name).isEqualTo(401);
          assertThat(MAPPER.readTree(response.body()).get("code").asText())
              .as(name)
              .isEqualTo("UNAUTHENTICATED");
        });
  }

  // Kịch bản: token đúng khóa, đúng aud nhưng issuer khác bị chặn
  // Input: token ký bởi TestJwt với iss "evil-issuer"
  // Output: 401 UNAUTHENTICATED
  @Test
  void wrongIssuerGets401() throws Exception {
    HttpResponse<String> response =
        get(JWT.tokenWithIssuer(UUID.randomUUID(), "mallory", "evil-issuer"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(MAPPER.readTree(response.body()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  // Dựng claim giống token của Identity cho user và audience cho trước
  private static JWTClaimsSet claims(UUID user, String audience) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .subject(user.toString())
        .issuer("nexus-identity")
        .audience(audience)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
        .build();
  }

  // Tấn công nhầm thuật toán: ký HS256 với bí mật là byte khóa công khai RSA lấy từ JWKS
  private static String hs256WithPublicKey(JWTClaimsSet claims) {
    try {
      RSAKey publicKey = JWKSet.parse(JWT.jwks()).getKeys().get(0).toRSAKey();
      byte[] secret = publicKey.toRSAPublicKey().getEncoded();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(publicKey.getKeyID()).build(),
              claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Gọi GET /api/v1/servers kèm Bearer token cho trước
  private HttpResponse<String> get(String token) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/servers"))
              .header("Authorization", "Bearer " + token)
              .build();
      return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

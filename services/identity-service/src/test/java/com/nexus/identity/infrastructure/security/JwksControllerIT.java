package com.nexus.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.identity.IdentityIntegrationTest;
import com.nexus.identity.application.port.TokenIssuer;
import com.nexus.identity.domain.user.DisplayName;
import com.nexus.identity.domain.user.Email;
import com.nexus.identity.domain.user.PasswordHash;
import com.nexus.identity.domain.user.User;
import com.nexus.identity.domain.user.UserId;
import com.nexus.identity.domain.user.Username;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Kiểm tra GET /.well-known/jwks.json chỉ công bố public key và verify được token của Identity
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwksControllerIT extends IdentityIntegrationTest {

  @Autowired private TokenIssuer tokenIssuer;
  @LocalServerPort private int port;

  // Kịch bản: JWKS chỉ có một khóa RSA công khai, đủ trường định danh, không có thành phần riêng
  // Input: GET /.well-known/jwks.json không kèm token
  // Output: 200 JSON; kty, n, e, kid=nexus-1, alg=RS256, use=sig; không có d,p,q,dp,dq,qi
  @Test
  void jwksExposesOnlyPublicKey() throws Exception {
    HttpResponse<String> response = fetchJwks();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .get()
        .asString()
        .startsWith("application/json");
    JsonNode keys = JsonMapper.builder().build().readTree(response.body()).get("keys");
    assertThat(keys).hasSize(1);
    JsonNode key = keys.get(0);
    assertThat(key.get("kty").asText()).isEqualTo("RSA");
    assertThat(key.get("kid").asText()).isEqualTo("nexus-1");
    assertThat(key.get("alg").asText()).isEqualTo("RS256");
    assertThat(key.get("use").asText()).isEqualTo("sig");
    assertThat(key.has("n")).isTrue();
    assertThat(key.has("e")).isTrue();
    for (String member : new String[] {"d", "p", "q", "dp", "dq", "qi"}) {
      assertThat(key.has(member)).as(member).isFalse();
    }
  }

  // Kịch bản: token do TokenIssuer phát verify được bằng khóa lấy từ JWKS công bố
  // Input: token cho user mới, public key parse từ body /.well-known/jwks.json
  // Output: decode thành công, sub bằng id user
  @Test
  void tokenFromIssuerVerifiesAgainstServedJwks() throws Exception {
    User user =
        User.register(
            UserId.newId(),
            new Email("bob@x.io"),
            new Username("bob"),
            new DisplayName("Bob"),
            new PasswordHash("$2a$12$hash"),
            Instant.now().truncatedTo(ChronoUnit.MICROS));
    String token = tokenIssuer.issue(user).value();

    RSAKey served = (RSAKey) JWKSet.parse(fetchJwks().body()).getKeys().get(0);
    var jwt = NimbusJwtDecoder.withPublicKey(served.toRSAPublicKey()).build().decode(token);

    assertThat(jwt.getSubject()).isEqualTo(user.id().value().toString());
  }

  // Gọi GET /.well-known/jwks.json trên cổng ứng dụng
  private HttpResponse<String> fetchJwks() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/jwks.json"))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}

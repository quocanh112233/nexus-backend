package com.nexus.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Kiểm tra TestJwt ký token verify được bằng JWKS của chính nó và JWKS phục vụ qua HTTP
class TestJwtTest {

  // Kịch bản: token verify được bằng JWKS của chính nó, token của bộ ký khác thì không
  // Input: hai TestJwt độc lập, mỗi bên ký một token cho cùng user
  // Output: token của A verify đạt với JWKS của A và trượt với JWKS của B, sub và aud đúng
  @Test
  void tokenVerifiesOnlyAgainstOwnJwks() throws Exception {
    TestJwt a = TestJwt.create();
    TestJwt b = TestJwt.create();
    UUID user = UUID.randomUUID();

    SignedJWT jwt = SignedJWT.parse(a.token(user, "alice"));

    assertThat(jwt.verify(verifierOf(a))).isTrue();
    assertThat(jwt.verify(verifierOf(b))).isFalse();
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.toString());
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("nexus");
    assertThat(a.jwks()).doesNotContain("\"d\"");
  }

  // Kịch bản: máy chủ JWKS giả trả đúng JWKS qua HTTP tại cổng ngẫu nhiên
  // Input: serveJwks rồi GET uri()
  // Output: 200 và body bằng jwks() của bộ ký
  @Test
  void serveJwksReturnsPublicKeySet() throws Exception {
    TestJwt jwt = TestJwt.create();

    try (TestJwt.JwksServer server = jwt.serveJwks()) {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(server.uri())).build(),
                  HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo(jwt.jwks());
    }
  }

  // Dựng bộ verify từ khóa công khai trong JWKS của bộ ký
  private static RSASSAVerifier verifierOf(TestJwt jwt) throws Exception {
    try {
      return new RSASSAVerifier(
          ((RSAKey) JWKSet.parse(jwt.jwks()).getKeys().get(0)).toRSAPublicKey());
    } catch (ParseException e) {
      throw new IllegalStateException(e);
    }
  }
}

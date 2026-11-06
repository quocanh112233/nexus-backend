package com.nexus.testsupport;

import com.nimbusds.jose.JOSEException;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

// Bộ ký JWT RS256 cho test: khóa RSA riêng, JWKS công khai và máy chủ JWKS giả dùng chung
public final class TestJwt {

  private static final String KID = "test-1";
  private static final String ISSUER = "nexus-identity";
  private static final String AUDIENCE = "nexus";

  private final RSAKey key;

  // Giữ cặp khóa RSA đã sinh
  private TestJwt(RSAKey key) {
    this.key = key;
  }

  // Sinh bộ ký mới với khóa RSA 2048 và kid cố định
  public static TestJwt create() {
    try {
      return new TestJwt(new RSAKeyGenerator(2048).keyID(KID).generate());
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  // Trả JWKS công khai dạng JSON, không chứa thành phần khóa riêng
  public String jwks() {
    return new JWKSet(key.toPublicJWK()).toString();
  }

  // Token RS256 hợp lệ 15 phút cho user
  public String token(UUID userId, String username) {
    return token(userId, username, Instant.now().plus(Duration.ofMinutes(15)));
  }

  // Token RS256 với thời điểm hết hạn tùy chọn
  public String token(UUID userId, String username, Instant exp) {
    return sign(JWSAlgorithm.RS256, userId, username, ISSUER, AUDIENCE, exp);
  }

  // Token RS256 hợp lệ 15 phút nhưng audience tùy chọn
  public String tokenWithAudience(UUID userId, String username, String aud) {
    return sign(
        JWSAlgorithm.RS256,
        userId,
        username,
        ISSUER,
        aud,
        Instant.now().plus(Duration.ofMinutes(15)));
  }

  // Token RS256 hợp lệ 15 phút nhưng issuer tùy chọn
  public String tokenWithIssuer(UUID userId, String username, String iss) {
    return sign(
        JWSAlgorithm.RS256,
        userId,
        username,
        iss,
        AUDIENCE,
        Instant.now().plus(Duration.ofMinutes(15)));
  }

  // Token hợp lệ 15 phút ký bằng cùng khóa RSA nhưng thuật toán RS* tùy chọn, như RS512
  public String tokenWithAlgorithm(UUID userId, String username, JWSAlgorithm alg) {
    return sign(
        alg, userId, username, ISSUER, AUDIENCE, Instant.now().plus(Duration.ofMinutes(15)));
  }

  // Dựng claim giống token của Identity rồi ký bằng khóa riêng với thuật toán cho trước
  private String sign(
      JWSAlgorithm alg, UUID userId, String username, String iss, String aud, Instant exp) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .claim("username", username)
            .issuer(iss)
            .audience(aud)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build();
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(KID).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  // Mở máy chủ HTTP cổng ngẫu nhiên phục vụ JWKS tại /jwks.json
  public JwksServer serveJwks() {
    return new JwksServer(jwks());
  }

  // Máy chủ JWKS giả dựa trên HttpServer của JDK
  public static final class JwksServer implements AutoCloseable {

    private final HttpServer server;

    // Khởi động máy chủ trên loopback, trả body JWKS cho /jwks.json
    private JwksServer(String body) {
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/jwks.json",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    // URL đầy đủ của JWKS
    public String uri() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
    }

    // Dừng máy chủ
    @Override
    public void close() {
      server.stop(0);
    }
  }
}

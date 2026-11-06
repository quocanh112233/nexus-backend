package com.nexus.community.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.nexus.testsupport.TestJwt;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

// Kiểm tra decoder không treo khi máy chủ JWKS nhận kết nối nhưng không trả lời
class JwksTimeoutTest {

  // Kịch bản: máy chủ JWKS treo, chỉ nhận kết nối rồi im lặng
  // Input: decode token hợp lệ với read timeout 300ms trỏ vào ServerSocket không phản hồi
  // Output: ném JwtException trong vài giây thay vì treo vô hạn
  @Test
  void hungJwksServerFailsFastInsteadOfBlocking() throws Exception {
    String token = TestJwt.create().token(UUID.randomUUID(), "user");
    try (ServerSocket hung = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      JwtDecoder decoder =
          new SecurityConfig()
              .jwtDecoder(
                  "http://127.0.0.1:" + hung.getLocalPort() + "/jwks",
                  "nexus-identity",
                  "nexus",
                  Duration.ofMillis(200),
                  Duration.ofMillis(300));

      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class));
    }
  }
}

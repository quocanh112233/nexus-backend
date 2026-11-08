package com.nexus.community;

import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.TestJwt;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

// Lớp cha IT community: Postgres, JWKS giả dùng chung cả JVM, relay và listener AMQP mặc định tắt
@TestPropertySource(
    properties = {
      "nexus.messaging.relay.enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false"
    })
public abstract class CommunityIntegrationTest extends PostgresIntegrationTest {

  protected static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();

  // Trỏ app.jwt.jwks-uri tới máy chủ JWKS giả của TestJwt và để gRPC chọn cổng ngẫu nhiên
  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    registry.add("app.jwt.jwks-uri", JWKS::uri);
    registry.add("spring.grpc.server.port", () -> 0);
  }

  // Trả header Authorization Bearer hợp lệ cho user bất kỳ
  protected static String bearer(UUID userId) {
    return "Bearer " + JWT.token(userId, "user-" + userId.toString().substring(0, 8));
  }
}

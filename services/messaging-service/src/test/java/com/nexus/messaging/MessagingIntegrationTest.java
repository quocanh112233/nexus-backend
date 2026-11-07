package com.nexus.messaging;

import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.TestJwt;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

// Lớp cha cho IT của messaging: Postgres, Redis dùng chung và một máy chủ JWKS giả cho cả JVM
public abstract class MessagingIntegrationTest extends PostgresIntegrationTest {

  protected static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static {
    REDIS.start();
  }

  // Trỏ JWKS tới máy chủ giả, Redis tới container, và để gRPC chọn cổng ngẫu nhiên
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    registry.add("app.jwt.jwks-uri", JWKS::uri);
    registry.add("spring.grpc.server.port", () -> 0);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  // Trả header Authorization Bearer hợp lệ cho user bất kỳ
  protected static String bearer(UUID userId) {
    return "Bearer " + JWT.token(userId, "user-" + userId.toString().substring(0, 8));
  }
}

package com.nexus.community;

import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.TestJwt;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Lớp cha cho IT của community: Postgres dùng chung và một máy chủ JWKS giả cho cả JVM
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

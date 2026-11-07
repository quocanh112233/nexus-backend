package com.nexus.identity;

import com.nexus.testsupport.PostgresIntegrationTest;
import java.security.KeyPair;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Lớp cha cho IT của identity: Postgres dùng chung và một khóa ký JWT sinh lúc chạy cho cả JVM
public abstract class IdentityIntegrationTest extends PostgresIntegrationTest {

  protected static final String TEST_CLOUD = "test-cloud";
  protected static final String TEST_SECRET = "test-secret";
  protected static final String TEST_ROOT = "nexus-test";
  protected static final KeyPair JWT_KEYS = TestJwtKeys.generate();

  private static final String JWT_KEY_PATH = TestJwtKeys.writePem(JWT_KEYS.getPrivate()).toString();

  // Trỏ khóa JWT tới PEM vừa sinh, nạp Cloudinary giả, gRPC chọn cổng ngẫu nhiên
  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.jwt.private-key-path", () -> JWT_KEY_PATH);
    registry.add("app.cloudinary.cloud-name", () -> TEST_CLOUD);
    registry.add("app.cloudinary.api-key", () -> "test-key");
    registry.add("app.cloudinary.api-secret", () -> TEST_SECRET);
    registry.add("app.cloudinary.upload-preset", () -> "test_preset");
    registry.add("app.cloudinary.folder-root", () -> TEST_ROOT);
    registry.add("spring.grpc.server.port", () -> 0);
  }
}

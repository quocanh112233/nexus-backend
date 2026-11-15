package com.nexus.notification;

import com.nexus.testsupport.PostgresIntegrationTest;
import com.nexus.testsupport.TestJwt;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

// Lớp cha IT notification: Postgres, JWKS giả dùng chung cả JVM, relay, AMQP, backfill mặc định tắt
@TestPropertySource(
    properties = {
      "nexus.messaging.relay.enabled=false",
      "nexus.notification.backfill.enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false"
    })
public abstract class NotificationIntegrationTest extends PostgresIntegrationTest {

  protected static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();

  // Trỏ JWKS tới máy chủ giả
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    registry.add("app.jwt.jwks-uri", JWKS::uri);
  }

  // Trả header Authorization Bearer hợp lệ cho user bất kỳ
  protected static String bearer(UUID userId) {
    return "Bearer " + JWT.token(userId, "user-" + userId.toString().substring(0, 8));
  }
}

package com.nexus.presence.interfaces.rest;

import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.RedisContainer;
import com.nexus.testsupport.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Lớp cha IT REST: Redis, RabbitMQ thật, JWKS giả, Community gRPC trỏ vào cổng chết
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.grpc.service-token=test-token",
      "app.grpc.community.target=localhost:1"
    })
abstract class PresenceApiIntegrationTest {

  private static final TestJwt JWT = TestJwt.create();
  private static final TestJwt.JwksServer JWKS = JWT.serveJwks();

  @LocalServerPort private int port;

  // Trỏ broker (vhost "/"), Redis và JWKS tới máy chủ giả
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    RedisContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
    registry.add("app.jwt.jwks-uri", JWKS::uri);
  }

  // Trả header Authorization Bearer hợp lệ cho user bất kỳ
  static String bearer(UUID userId) {
    return "Bearer " + JWT.token(userId, "user-" + userId.toString().substring(0, 8));
  }

  // GET /api/v1/presence với query thô, bearer null thì không gửi header Authorization
  HttpResponse<String> getPresence(String query, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/presence" + query));
    if (bearer != null) {
      request.header("Authorization", bearer);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}

package com.nexus.presence.application.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nexus.presence.PresenceChangeTap;
import com.nexus.testsupport.RabbitMqContainer;
import com.nexus.testsupport.RedisContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Kiểm tra job dọn instance chết với Redis và RabbitMQ thật, TTL 2 giây và job mỗi 500 ms
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.grpc.service-token=test-token",
      "app.grpc.community.target=localhost:1",
      "app.presence.alive-ttl=2s",
      "app.presence.dead-instance-interval=500ms"
    })
class DeadInstanceIT {

  @Autowired private SessionService service;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private RabbitAdmin admin;
  @Autowired private StringRedisTemplate redis;

  private PresenceChangeTap tap;
  private final UUID user = UUID.randomUUID();

  // Trỏ broker (vhost "/") và Redis tới container
  @DynamicPropertySource
  static void infra(DynamicPropertyRegistry registry) {
    RabbitMqContainer.register(registry);
    RedisContainer.register(registry);
    registry.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  // Dựng queue tạm nhận PresenceChanged
  @BeforeEach
  void setUp() {
    tap = new PresenceChangeTap(admin, rabbit);
  }

  // Xóa queue tạm
  @AfterEach
  void tearDown() {
    tap.close();
  }

  // Kịch bản: gateway rt1 chết im lặng sau một heartbeat (giá trị thật 60 s/15 s ở application.yml)
  // Input: connect + heartbeat một lần, khóa alive TTL 2 giây rồi hết hạn
  // Output: nhận PresenceChanged OFFLINE của user và status bị xóa
  @Test
  void killedInstanceUsersGoOfflineWithinNinetySeconds() {
    String rt1 = "rt1-" + UUID.randomUUID();
    service.connect("s-" + rt1, user, rt1, Instant.now());
    service.heartbeat(rt1, Map.of("s-" + rt1, user), Instant.now());
    tap.awaitStatus(user, "ONLINE");

    tap.awaitStatus(user, "OFFLINE");

    assertThat(redis.hasKey("presence:status:" + user)).isFalse();
  }

  // Kịch bản: user có session ở instance chết và ở instance còn heartbeat
  // Input: s1 ở dead, s2 ở live (heartbeat mỗi 500 ms), chờ dead bị dọn
  // Output: không có OFFLINE nào, status vẫn ONLINE
  @Test
  void userWithSessionOnAnotherInstanceStaysOnline() throws Exception {
    String dead = "dead-" + UUID.randomUUID();
    String live = "live-" + UUID.randomUUID();
    service.connect("s1-" + dead, user, dead, Instant.now());
    service.connect("s2-" + live, user, live, Instant.now());
    Instant until = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(until)) {
      service.heartbeat(live, Map.of("s2-" + live, user), Instant.now());
      Thread.sleep(500);
    }

    await().until(() -> redis.opsForSet().isMember("presence:instances", dead) == Boolean.FALSE);
    assertThat(tap.statuses(user)).containsExactly("ONLINE");
    assertThat(redis.opsForValue().get("presence:status:" + user)).isEqualTo("ONLINE");
  }
}

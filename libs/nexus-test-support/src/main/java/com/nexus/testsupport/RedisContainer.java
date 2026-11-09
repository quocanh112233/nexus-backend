package com.nexus.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

// Container redis:7-alpine dùng chung cả JVM, cho IT cần Redis thật
public final class RedisContainer {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  // Lớp tiện ích, không khởi tạo
  private RedisContainer() {}

  // Khởi động container nếu chưa chạy
  public static synchronized void start() {
    if (!REDIS.isRunning()) {
      REDIS.start();
    }
  }

  // Địa chỉ host của container
  public static String host() {
    start();
    return REDIS.getHost();
  }

  // Cổng đã map ra host
  public static int port() {
    start();
    return REDIS.getMappedPort(6379);
  }

  // Đẩy host, port của container vào cấu hình spring.data.redis
  public static void register(DynamicPropertyRegistry registry) {
    start();
    registry.add("spring.data.redis.host", RedisContainer::host);
    registry.add("spring.data.redis.port", RedisContainer::port);
  }
}

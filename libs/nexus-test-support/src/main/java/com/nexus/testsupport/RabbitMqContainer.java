package com.nexus.testsupport;

import com.github.dockerjava.api.DockerClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

// Container rabbitmq:4-management-alpine dùng chung cả JVM, có pause/unpause để giả lập broker chết
public final class RabbitMqContainer {

  private static final RabbitMQContainer RABBIT =
      new RabbitMQContainer("rabbitmq:4-management-alpine");

  // Lớp tiện ích, không khởi tạo
  private RabbitMqContainer() {}

  // Khởi động container nếu chưa chạy
  public static synchronized void start() {
    if (!RABBIT.isRunning()) {
      RABBIT.start();
    }
  }

  // Đẩy host, port, user, password của container vào cấu hình spring.rabbitmq
  public static void register(DynamicPropertyRegistry registry) {
    start();
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }

  // Tạm dừng container, cổng giữ nguyên
  public static void pause() {
    docker().pauseContainerCmd(RABBIT.getContainerId()).exec();
  }

  // Chạy lại container đã pause
  public static void unpause() {
    docker().unpauseContainerCmd(RABBIT.getContainerId()).exec();
  }

  // Lấy client Docker của Testcontainers
  private static DockerClient docker() {
    return DockerClientFactory.instance().client();
  }
}

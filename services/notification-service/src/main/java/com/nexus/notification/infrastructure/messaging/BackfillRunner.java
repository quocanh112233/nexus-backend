package com.nexus.notification.infrastructure.messaging;

import com.nexus.notification.application.backfill.BackfillMembershipUseCase;
import com.nexus.notification.application.port.BackfillSourceUnavailableException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

// Khai báo queue, nạp membership rồi mới start listener; ponytail: một instance, nhiều cần khóa
@Component
class BackfillRunner implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(BackfillRunner.class);

  private final AmqpAdmin admin;
  private final RabbitListenerEndpointRegistry registry;
  private final BackfillMembershipUseCase backfill;
  private final boolean listenersEnabled;
  private final boolean backfillEnabled;
  private final int maxAttempts;
  private final Duration retryDelay;
  private volatile boolean running;

  // Nhận AmqpAdmin, registry listener, use case backfill và cấu hình bật, số lần, độ trễ thử lại
  BackfillRunner(
      AmqpAdmin admin,
      RabbitListenerEndpointRegistry registry,
      BackfillMembershipUseCase backfill,
      @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean listenersEnabled,
      @Value("${nexus.notification.backfill.enabled:true}") boolean backfillEnabled,
      @Value("${nexus.notification.backfill.max-attempts:5}") int maxAttempts,
      @Value("${nexus.notification.backfill.retry-delay:2s}") Duration retryDelay) {
    this.admin = admin;
    this.registry = registry;
    this.backfill = backfill;
    this.listenersEnabled = listenersEnabled;
    this.backfillEnabled = backfillEnabled;
    this.maxAttempts = maxAttempts;
    this.retryDelay = retryDelay;
  }

  // Tắt listener thì bỏ hết; ngược lại khai báo queue, backfill có thử lại rồi start hai listener
  @Override
  public void start() {
    if (listenersEnabled) {
      prepareWithRetry();
      registry.getListenerContainer("community-events").start();
      registry.getListenerContainer("messaging-events").start();
    }
    running = true;
  }

  // Khai báo queue rồi backfill, lỗi nguồn hay broker thì chờ gấp đôi, hết lần thì hỏng khởi động
  private void prepareWithRetry() {
    Duration delay = retryDelay;
    for (int attempt = 1; ; attempt++) {
      try {
        admin.initialize();
        if (backfillEnabled) {
          LOG.info("Backfill membership-v1: {}", backfill.execute() ? "đã nạp" : "đã có, bỏ qua");
        }
        return;
      } catch (BackfillSourceUnavailableException | AmqpException e) {
        if (attempt >= maxAttempts) {
          throw new IllegalStateException("Backfill thất bại sau " + attempt + " lần thử", e);
        }
        LOG.warn("Backfill lần {} thất bại, thử lại sau {}", attempt, delay, e);
        sleep(delay);
        delay = delay.multipliedBy(2);
      }
    }
  }

  // Ngủ trong khoảng delay, bị ngắt thì giữ cờ ngắt và hỏng khởi động
  private static void sleep(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Backfill bị ngắt", e);
    }
  }

  // Listener do registry dừng khi tắt, ở đây chỉ hạ cờ
  @Override
  public void stop() {
    running = false;
  }

  // True sau khi start xong
  @Override
  public boolean isRunning() {
    return running;
  }

  // Chạy trước web server để không phục vụ HTTP khi projection chưa nạp xong
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE - 4096;
  }
}

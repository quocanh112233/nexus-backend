package com.nexus.consumerapp.probe;

import com.nexus.common.messaging.EventEnvelope;
import com.nexus.common.messaging.EventEnvelopeReader;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Listener thăm dò queue it.events: đếm số lần gọi nghiệp vụ, hành vi do test cấu hình
@Component
@ConditionalOnProperty("it.probe.enabled")
public class ProbeListener {

  private final EventEnvelopeReader reader;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile Consumer<EventEnvelope> behavior = e -> {};

  // Dựng listener với reader envelope
  ProbeListener(EventEnvelopeReader reader) {
    this.reader = reader;
  }

  // Đọc envelope rồi chạy nghiệp vụ giả
  @RabbitListener(queues = "it.events")
  public void on(Message message) {
    EventEnvelope envelope = reader.read(message);
    calls.incrementAndGet();
    behavior.accept(envelope);
  }

  // Số lần nghiệp vụ được gọi
  public int calls() {
    return calls.get();
  }

  // Xóa bộ đếm và đưa hành vi về không làm gì
  public void reset() {
    calls.set(0);
    behavior = e -> {};
  }

  // Đặt hành vi nghiệp vụ tùy ý
  public void behave(Consumer<EventEnvelope> behavior) {
    this.behavior = behavior;
  }

  // Nghiệp vụ luôn ném lỗi này
  public void failWith(RuntimeException error) {
    behavior =
        e -> {
          throw error;
        };
  }

  // Nghiệp vụ ngủ một khoảng rồi trả về
  public void sleep(Duration duration) {
    behavior =
        e -> {
          try {
            Thread.sleep(duration);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        };
  }
}

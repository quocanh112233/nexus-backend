package com.nexus.realtime.infrastructure.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

// Unit test PresenceReporter: shutdown khi broker lỗi và session đóng trước khi kịp mở
class PresenceReporterTest {

  private final RealtimeEventPublisher publisher = mock(RealtimeEventPublisher.class);
  private final PresenceReporter reporter =
      new PresenceReporter(publisher, new InstanceId("i"), Clock.systemUTC());

  // Kịch bản: broker hỏng lúc dừng graceful với ba session mở
  // Input: publishSessionDisconnected trả false, gọi shutdown
  // Output: publisher chỉ được gọi một lần, các session còn lại bị bỏ qua
  @Test
  void shutdownStopsAfterFirstPublishFailure() {
    when(publisher.publishSessionDisconnected(anyString(), any(), anyString())).thenReturn(false);
    for (String id : List.of("s1", "s2", "s3")) {
      reporter.opened(id, UUID.randomUUID());
    }

    reporter.shutdown();

    verify(publisher, times(1)).publishSessionDisconnected(anyString(), any(), eq("i"));
  }

  // Kịch bản: sự kiện đóng chạy trước sự kiện mở của cùng session
  // Input: closed("s") rồi opened("s", user), sau đó heartbeat
  // Output: không phát SessionConnected, heartbeat có danh sách session rỗng
  @Test
  void closedBeforeOpenedLeavesNoGhostSession() {
    reporter.closed("s");
    reporter.opened("s", UUID.randomUUID());

    reporter.heartbeat();

    verify(publisher, never()).publishSessionConnected(anyString(), any(), anyString(), any());
    verify(publisher).publishHeartbeat(eq("i"), eq(Map.of()), any());
  }

  // Kịch bản: closed và opened của cùng session chạy song song, lặp nhiều lần
  // Input: hai luồng gọi closed(s) và opened(s, user) đồng thời qua barrier, 2000 vòng
  // Output: sau mọi vòng không còn session ma, heartbeat có danh sách session rỗng
  @Test
  void concurrentOpenedAndClosedNeverLeaveGhostSession() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2000; i++) {
        String id = "s" + i;
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> open =
            pool.submit(
                () -> {
                  barrier.await();
                  reporter.opened(id, UUID.randomUUID());
                  return null;
                });
        Future<?> close =
            pool.submit(
                () -> {
                  barrier.await();
                  reporter.closed(id);
                  return null;
                });
        open.get();
        close.get();
      }
    } finally {
      pool.shutdownNow();
    }

    reporter.heartbeat();

    verify(publisher).publishHeartbeat(eq("i"), eq(Map.of()), any());
  }
}

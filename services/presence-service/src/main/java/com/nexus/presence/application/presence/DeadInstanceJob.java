package com.nexus.presence.application.presence;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Định kỳ tìm instance gateway chết im lặng để dọn (mặc định 15 s, cấu hình được)
@Component
public class DeadInstanceJob {

  private final PurgeDeadInstancesUseCase useCase;

  // Nhận use case dọn instance chết
  public DeadInstanceJob(PurgeDeadInstancesUseCase useCase) {
    this.useCase = useCase;
  }

  // Chạy một lượt dọn
  @Scheduled(
      fixedRateString = "${app.presence.dead-instance-interval:15s}",
      initialDelayString = "${app.presence.dead-instance-interval:15s}")
  void run() {
    useCase.execute();
  }
}

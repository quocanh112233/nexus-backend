package com.nexus.realtime;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

// Điểm khởi động của realtime-gateway
@SpringBootApplication
@EnableScheduling
public class RealtimeGatewayApplication {

  // Đồng hồ hệ thống để các bean có thể thay bằng đồng hồ giả trong test
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  // Chạy ứng dụng Spring Boot
  public static void main(String[] args) {
    SpringApplication.run(RealtimeGatewayApplication.class, args);
  }
}

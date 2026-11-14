package com.nexus.notification;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

// Điểm khởi động của notification-service
@SpringBootApplication
public class NotificationServiceApplication {

  // Chạy ứng dụng Spring Boot
  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }

  // Đồng hồ UTC dùng chung cho use case và outbox
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}

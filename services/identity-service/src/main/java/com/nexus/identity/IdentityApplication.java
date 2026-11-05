package com.nexus.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Điểm khởi động của identity-service
@SpringBootApplication
public class IdentityApplication {

  // Chạy ứng dụng Spring Boot
  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}

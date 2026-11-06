package com.nexus.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Điểm khởi động của api-gateway
@SpringBootApplication
public class GatewayApplication {

  // Chạy ứng dụng Spring Boot
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}

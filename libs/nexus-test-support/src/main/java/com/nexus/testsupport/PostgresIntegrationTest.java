package com.nexus.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Lớp cha cho IT: một container postgres:16-alpine dùng chung cả JVM, nối datasource Spring vào đó
public abstract class PostgresIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  // Đẩy url, user, password của container vào cấu hình datasource
  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}

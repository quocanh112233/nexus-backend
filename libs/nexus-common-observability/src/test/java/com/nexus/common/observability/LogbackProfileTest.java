package com.nexus.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.mock.env.MockEnvironment;

// Khởi tạo Logback thật từ logback-spring.xml của lib để kiểm tra từng profile
class LogbackProfileTest {

  private final LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());

  private Properties systemProperties;

  // Chụp bản sao system property trước test vì LoggingSystemProperties ghi vào JVM
  @BeforeEach
  void snapshot() {
    systemProperties = (Properties) System.getProperties().clone();
  }

  // Đưa Logback về trạng thái trước khi khởi tạo rồi luôn khôi phục nguyên vẹn system property
  @AfterEach
  void reset() {
    try {
      system.cleanUp();
      system.beforeInitialize();
    } finally {
      System.getProperties().clear();
      System.getProperties().putAll(systemProperties);
    }
    assertThat(System.getProperties()).isEqualTo(systemProperties);
  }

  // Nạp logback-spring.xml với môi trường đã cho rồi trả về encoder của appender CONSOLE
  private Object consoleEncoder(MockEnvironment env) {
    new LoggingSystemProperties(env).apply();
    system.cleanUp();
    system.beforeInitialize();
    system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    assertThat(root.getAppender("OTEL")).isNotNull();
    return ((ConsoleAppender<?>) root.getAppender("CONSOLE")).getEncoder();
  }

  // Kịch bản: profile local đặt logging.structured.format.console rỗng để in text thường
  // Input: profile local, format console là chuỗi rỗng
  // Output: khởi tạo không lỗi, CONSOLE dùng encoder pattern
  @Test
  void localProfileUsesPlainTextConsole() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");
    env.setProperty("logging.structured.format.console", "");

    assertThat(consoleEncoder(env)).isInstanceOf(PatternLayoutEncoder.class);
  }

  // Kịch bản: profile mặc định bật JSON ECS cho console
  // Input: không có profile local, format console là ecs
  // Output: CONSOLE dùng StructuredLogEncoder
  @Test
  void defaultProfileUsesStructuredConsole() {
    MockEnvironment env =
        new MockEnvironment().withProperty("logging.structured.format.console", "ecs");

    assertThat(consoleEncoder(env)).isInstanceOf(StructuredLogEncoder.class);
  }
}

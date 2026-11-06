package com.nexus.common.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

// Nối appender Logback OpenTelemetry với bean OpenTelemetry để log đi qua SdkLoggerProvider
@AutoConfiguration
public class OtelLogAppenderAutoConfiguration {

  // Cài OpenTelemetry vào appender sau khi context khởi tạo, bỏ qua nếu SDK bị tắt
  @Bean
  InitializingBean otelLogAppenderInstaller(ObjectProvider<OpenTelemetry> openTelemetry) {
    return () -> openTelemetry.ifAvailable(OpenTelemetryAppender::install);
  }
}

package com.nexus.common.observability;

import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

// Tắt exporter OTLP khi endpoint trống hoặc không đặt, cấu hình tường minh khác vẫn thắng
public class OtelExportEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String SOURCE_NAME = "nexusOtelExportOff";

  private static final List<String> ENDPOINT_KEYS =
      List.of("OTEL_EXPORTER_OTLP_ENDPOINT", "otel.exporter.otlp.endpoint");

  // Thêm property source ưu tiên thấp nhất đặt ba cờ export về false khi chưa có endpoint
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    for (String key : ENDPOINT_KEYS) {
      String endpoint = environment.getProperty(key);
      if (endpoint != null && !endpoint.isBlank()) {
        return;
      }
    }
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                SOURCE_NAME,
                Map.of(
                    "management.otlp.metrics.export.enabled", "false",
                    "management.tracing.export.otlp.enabled", "false",
                    "management.logging.export.otlp.enabled", "false")));
  }
}

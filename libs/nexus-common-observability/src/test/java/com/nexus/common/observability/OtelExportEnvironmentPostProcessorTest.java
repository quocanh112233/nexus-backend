package com.nexus.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

// Kiểm tra cờ export OTLP được tắt hay giữ nguyên tùy endpoint
class OtelExportEnvironmentPostProcessorTest {

  private final OtelExportEnvironmentPostProcessor processor =
      new OtelExportEnvironmentPostProcessor();

  // Kịch bản: không đặt endpoint nên mọi exporter phải tắt
  // Input: môi trường không có OTEL_EXPORTER_OTLP_ENDPOINT
  // Output: ba cờ export đều là false
  @Test
  void disablesAllExportersWhenEndpointMissing() {
    MockEnvironment env = new MockEnvironment();
    processor.postProcessEnvironment(env, null);
    assertThat(env.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.logging.export.otlp.enabled")).isEqualTo("false");
  }

  // Kịch bản: endpoint chỉ gồm khoảng trắng (compose để trống) cũng coi như chưa đặt
  // Input: OTEL_EXPORTER_OTLP_ENDPOINT là một dấu cách
  // Output: cờ export metric là false
  @Test
  void treatsBlankEndpointAsMissing() {
    MockEnvironment env = new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", " ");
    processor.postProcessEnvironment(env, null);
    assertThat(env.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
  }

  // Kịch bản: có endpoint nên để mặc định của Boot bật export
  // Input: OTEL_EXPORTER_OTLP_ENDPOINT là http://otel-lgtm:4318
  // Output: không có property source tắt export
  @Test
  void keepsExportersWhenEndpointSet() {
    MockEnvironment env =
        new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-lgtm:4318");
    processor.postProcessEnvironment(env, null);
    assertThat(env.getPropertySources().contains(OtelExportEnvironmentPostProcessor.SOURCE_NAME))
        .isFalse();
  }

  // Kịch bản: người dùng bật tường minh cờ export dù endpoint trống thì cấu hình đó thắng
  // Input: management.otlp.metrics.export.enabled=true trong môi trường, không có endpoint
  // Output: metric vẫn true, hai cờ còn lại bị tắt
  @Test
  void explicitSettingWinsOverOffSwitch() {
    MockEnvironment env =
        new MockEnvironment().withProperty("management.otlp.metrics.export.enabled", "true");
    processor.postProcessEnvironment(env, null);
    assertThat(env.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("true");
    assertThat(env.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
  }

  // Kịch bản: endpoint đặt qua system property dạng otel.exporter.otlp.endpoint
  // Input: otel.exporter.otlp.endpoint là http://otel-lgtm:4318
  // Output: không có property source tắt export
  @Test
  void honorsSystemPropertyFormEndpoint() {
    MockEnvironment env =
        new MockEnvironment().withProperty("otel.exporter.otlp.endpoint", "http://otel-lgtm:4318");
    processor.postProcessEnvironment(env, null);
    assertThat(env.getPropertySources().contains(OtelExportEnvironmentPostProcessor.SOURCE_NAME))
        .isFalse();
  }
}

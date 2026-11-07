package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm thử interceptor observation server do Boot đăng ký, auto-config của ta không đăng ký thêm
class ObservationInterceptorWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  GrpcServerObservationAutoConfiguration.class,
                  NexusCommonGrpcAutoConfiguration.class))
          .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class));

  // Kịch bản: có ObservationRegistry cùng auto-config của Boot và của ta
  // Input: context với ObservationRegistry và JwtDecoder giả
  // Output: một ObservationGrpcServerInterceptor global của Boot, @Order nhỏ hơn Auth
  @Test
  void bootRegistersSingleGlobalServerObservationInterceptor() {
    runner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ObservationGrpcServerInterceptor.class);
              assertThat(context.getBeansOfType(ObservationGrpcServerInterceptor.class))
                  .containsOnlyKeys("grpcServerObservationInterceptor");
              assertThat(
                      context.findAnnotationOnBean(
                          "grpcServerObservationInterceptor", GlobalServerInterceptor.class))
                  .isNotNull();
              assertThat(
                      context
                          .findAnnotationOnBean("grpcServerObservationInterceptor", Order.class)
                          .value())
                  .isLessThan(NexusCommonGrpcAutoConfiguration.AUTH_ORDER);
            });
  }

  // Kịch bản: thiếu ObservationRegistry
  // Input: context không có ObservationRegistry
  // Output: không có ObservationGrpcServerInterceptor nào
  @Test
  void noServerObservationInterceptorWithoutRegistry() {
    runner.run(
        context -> assertThat(context).doesNotHaveBean(ObservationGrpcServerInterceptor.class));
  }
}

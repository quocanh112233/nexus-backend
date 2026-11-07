package com.nexus.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// Kiểm thử điều kiện đăng ký interceptor toàn cục của NexusCommonGrpcAutoConfiguration
class NexusCommonGrpcAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  NexusCommonGrpcAutoConfiguration.class,
                  GrpcServerOAuth2ResourceServerAutoConfiguration.class))
          .withBean(GrpcSecurity.class, () -> mock(GrpcSecurity.class));

  // Liệt kê tên bean được đăng ký làm interceptor toàn cục
  private static List<String> globalInterceptors(
      org.springframework.context.ApplicationContext context) {
    return Arrays.stream(context.getBeanDefinitionNames())
        .filter(name -> context.findAnnotationOnBean(name, GlobalServerInterceptor.class) != null)
        .sorted()
        .toList();
  }

  // Kịch bản: có bean JwtDecoder
  // Input: context với JwtDecoder giả
  // Output: có AuthServerInterceptor và ExceptionMappingServerInterceptor
  @Test
  void registersInterceptorsWhenJwtDecoderPresent() {
    runner
        .withBean(JwtDecoder.class, () -> token -> null)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthServerInterceptor.class);
              assertThat(context).hasSingleBean(ExceptionMappingServerInterceptor.class);
            });
  }

  // Kịch bản: không có bean JwtDecoder
  // Input: context trống
  // Output: không đăng ký interceptor nào
  @Test
  void backsOffWithoutJwtDecoder() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(AuthServerInterceptor.class);
          assertThat(context).doesNotHaveBean(ExceptionMappingServerInterceptor.class);
        });
  }

  // Kịch bản: Boot tự dựng interceptor JWT khi có JwtDecoder và GrpcSecurity
  // Input: context có JwtDecoder, GrpcSecurity và auto-config OAuth2 resource server của Boot
  // Output: chỉ hai interceptor của ta là global, interceptor của Boot không được đăng ký
  @Test
  void bootJwtInterceptorBacksOff() {
    runner
        .withBean(JwtDecoder.class, () -> token -> null)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("jwtAuthenticationProcessInterceptor");
              assertThat(globalInterceptors(context))
                  .containsExactly("authServerInterceptor", "exceptionMappingServerInterceptor");
            });
  }

  // Kịch bản: thứ tự interceptor toàn cục
  // Input: context có JwtDecoder
  // Output: @Order của AuthServerInterceptor nhỏ hơn ExceptionMappingServerInterceptor
  @Test
  void authInterceptorIsOutsideExceptionMapping() {
    runner
        .withBean(JwtDecoder.class, () -> token -> null)
        .run(
            context -> {
              int auth = context.findAnnotationOnBean("authServerInterceptor", Order.class).value();
              int mapping =
                  context
                      .findAnnotationOnBean("exceptionMappingServerInterceptor", Order.class)
                      .value();
              assertThat(auth).isLessThan(mapping);
            });
  }
}
